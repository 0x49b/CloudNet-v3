/*
 * Copyright 2019-2021 CloudNetService team & contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.dytanic.cloudnet.wrapper;

import de.dytanic.cloudnet.common.log.LogManager;
import de.dytanic.cloudnet.common.log.Logger;
import de.dytanic.cloudnet.driver.CloudNetDriver;
import de.dytanic.cloudnet.driver.CloudNetVersion;
import de.dytanic.cloudnet.driver.DriverEnvironment;
import de.dytanic.cloudnet.driver.channel.ChannelMessage;
import de.dytanic.cloudnet.driver.module.DefaultModuleProviderHandler;
import de.dytanic.cloudnet.driver.network.INetworkChannel;
import de.dytanic.cloudnet.driver.network.buffer.DataBuf;
import de.dytanic.cloudnet.driver.network.def.NetworkConstants;
import de.dytanic.cloudnet.driver.network.netty.client.NettyNetworkClient;
import de.dytanic.cloudnet.driver.network.rpc.RPCSender;
import de.dytanic.cloudnet.driver.provider.service.RemoteCloudServiceFactory;
import de.dytanic.cloudnet.driver.service.ProcessSnapshot;
import de.dytanic.cloudnet.driver.service.ServiceConfiguration;
import de.dytanic.cloudnet.driver.service.ServiceId;
import de.dytanic.cloudnet.driver.service.ServiceInfoSnapshot;
import de.dytanic.cloudnet.driver.service.ServiceLifeCycle;
import de.dytanic.cloudnet.driver.service.ServiceTemplate;
import de.dytanic.cloudnet.driver.template.TemplateStorage;
import de.dytanic.cloudnet.wrapper.configuration.DocumentWrapperConfiguration;
import de.dytanic.cloudnet.wrapper.configuration.IWrapperConfiguration;
import de.dytanic.cloudnet.wrapper.database.DefaultWrapperDatabaseProvider;
import de.dytanic.cloudnet.wrapper.event.ApplicationPostStartEvent;
import de.dytanic.cloudnet.wrapper.event.ApplicationPreStartEvent;
import de.dytanic.cloudnet.wrapper.event.service.ServiceInfoSnapshotConfigureEvent;
import de.dytanic.cloudnet.wrapper.network.NetworkClientChannelHandler;
import de.dytanic.cloudnet.wrapper.network.listener.PacketAuthorizationResponseListener;
import de.dytanic.cloudnet.wrapper.permission.WrapperPermissionManagement;
import de.dytanic.cloudnet.wrapper.provider.WrapperGeneralCloudServiceProvider;
import de.dytanic.cloudnet.wrapper.provider.WrapperGroupConfigurationProvider;
import de.dytanic.cloudnet.wrapper.provider.WrapperMessenger;
import de.dytanic.cloudnet.wrapper.provider.WrapperNodeInfoProvider;
import de.dytanic.cloudnet.wrapper.provider.WrapperServiceTaskProvider;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.ApiStatus.Internal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * This class is the main class of the application wrapper, which performs the basic driver functions and the setup of
 * the application to be wrapped.
 *
 * @see CloudNetDriver
 */
public class Wrapper extends CloudNetDriver {

  private static final Path WORKING_DIRECTORY = Paths.get("");
  private static final Logger LOGGER = LogManager.getLogger(Wrapper.class);

  /**
   * The configuration of the wrapper, which was created from the CloudNet node. The properties are mirrored from the
   * configuration file.
   *
   * @see IWrapperConfiguration
   */
  private final IWrapperConfiguration config = DocumentWrapperConfiguration.load();

  private final RPCSender rpcSender;

  /**
   * The single task thread of the scheduler of the wrapper application
   */
  private final Thread mainThread = Thread.currentThread();

  /**
   * The ServiceInfoSnapshot instances. The current ServiceInfoSnapshot instance is the last send object snapshot from
   * this process. The lastServiceInfoSnapshot is the element which was send before.
   */
  private ServiceInfoSnapshot lastServiceInfoSnapShot = this.config.getServiceInfoSnapshot();
  private ServiceInfoSnapshot currentServiceInfoSnapshot = this.config.getServiceInfoSnapshot();

  protected Wrapper(@NotNull String[] args) {
    super(new ArrayList<>(Arrays.asList(args)));

    setInstance(this);

    this.cloudNetVersion = CloudNetVersion.fromClassInformation(Wrapper.class.getPackage());

    super.networkClient = new NettyNetworkClient(NetworkClientChannelHandler::new, this.config.getSSLConfig());
    this.rpcSender = this.rpcProviderFactory.providerForClass(this.networkClient, CloudNetDriver.class);

    super.databaseProvider = new DefaultWrapperDatabaseProvider(this);

    super.messenger = new WrapperMessenger(this);
    super.nodeInfoProvider = new WrapperNodeInfoProvider(this);
    super.serviceTaskProvider = new WrapperServiceTaskProvider(this);
    super.groupConfigurationProvider = new WrapperGroupConfigurationProvider(this);
    super.generalCloudServiceProvider = new WrapperGeneralCloudServiceProvider(this);

    super.cloudServiceFactory = new RemoteCloudServiceFactory(
      this.networkClient::getFirstChannel,
      this.networkClient,
      this.rpcProviderFactory);

    super.moduleProvider.setModuleProviderHandler(new DefaultModuleProviderHandler());
    super.moduleProvider.setModuleDirectoryPath(Paths.get(".wrapper", "modules"));

    super.setPermissionManagement(new WrapperPermissionManagement(this));
    super.driverEnvironment = DriverEnvironment.WRAPPER;
  }

  public static @NotNull Wrapper getInstance() {
    return (Wrapper) CloudNetDriver.getInstance();
  }

  @Override
  public synchronized void start() throws Exception {
    // load & enable the modules
    this.moduleProvider.loadAll().startAll();

    // connect to the node
    this.connectToNode();

    // initialize
    this.permissionManagement.init();
    Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

    // start the application
    if (!this.startApplication()) {
      System.exit(-1);
    }
  }

  @Override
  public void stop() {
    try {
      this.networkClient.close();
    } catch (Exception exception) {
      LOGGER.severe("Exception while closing the network client", exception);
    }

    this.scheduler.shutdownNow();
    this.moduleProvider.unloadAll();
    this.eventManager.unregisterAll();
    this.servicesRegistry.unregisterAll();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public @NotNull String getComponentName() {
    return this.getServiceId().getName();
  }

  @Override
  public @NotNull TemplateStorage getLocalTemplateStorage() {
    return this.getTemplateStorage(ServiceTemplate.LOCAL_STORAGE);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public @NotNull String getNodeUniqueId() {
    return this.getServiceId().getNodeUniqueId();
  }

  //TODO: add back when the TemplateStorage is implemented
  @Override
  public @NotNull TemplateStorage getTemplateStorage(String storage) {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public @NotNull Collection<TemplateStorage> getAvailableTemplateStorages() {
    return this.rpcSender.invokeMethod("getAvailableTemplateStorages").fireSync();
  }

  /**
   * Application wrapper implementation of this method. See the full documentation at the CloudNetDriver class.
   *
   * @see CloudNetDriver#sendCommandLineAsPermissionUser(UUID, String)
   */
  @Override
  public @NotNull Collection<String> sendCommandLineAsPermissionUser(@NotNull UUID uniqueId,
    @NotNull String commandLine) {
    return this.rpcSender.invokeMethod("sendCommandLineAsPermissionUser", uniqueId, commandLine).fireSync();
  }

  /**
   * Is an shortcut for Wrapper.getConfig().getServiceId()
   *
   * @return the ServiceId instance which was set in the config by the node
   */
  public @NotNull ServiceId getServiceId() {
    return this.getServiceConfiguration().getServiceId();
  }

  /**
   * Is an shortcut for Wrapper.getConfig().getServiceConfiguration()
   *
   * @return the first instance which was set in the config by the node
   */
  public @NotNull ServiceConfiguration getServiceConfiguration() {
    return this.config.getServiceConfiguration();
  }

  /**
   * Creates a completed new ServiceInfoSnapshot instance based of the properties of the current ServiceInfoSnapshot
   * instance
   *
   * @return the new ServiceInfoSnapshot instance
   */
  @NotNull
  public ServiceInfoSnapshot createServiceInfoSnapshot() {
    return new ServiceInfoSnapshot(
      System.currentTimeMillis(),
      this.currentServiceInfoSnapshot.getConnectedTime(),
      this.currentServiceInfoSnapshot.getAddress(),
      this.currentServiceInfoSnapshot.getConnectAddress(),
      ServiceLifeCycle.RUNNING,
      ProcessSnapshot.self(),
      this.getServiceConfiguration(),
      this.currentServiceInfoSnapshot.getProperties());
  }

  @Internal
  public ServiceInfoSnapshot configureServiceInfoSnapshot() {
    ServiceInfoSnapshot serviceInfoSnapshot = this.createServiceInfoSnapshot();
    this.configureServiceInfoSnapshot(serviceInfoSnapshot);
    return serviceInfoSnapshot;
  }

  private void configureServiceInfoSnapshot(ServiceInfoSnapshot serviceInfoSnapshot) {
    this.eventManager.callEvent(new ServiceInfoSnapshotConfigureEvent(serviceInfoSnapshot));

    this.lastServiceInfoSnapShot = this.currentServiceInfoSnapshot;
    this.currentServiceInfoSnapshot = serviceInfoSnapshot;
  }

  /**
   * This method should be used to send the current ServiceInfoSnapshot and all subscribers on the network and to update
   * their information. It calls the ServiceInfoSnapshotConfigureEvent before send the update to the node.
   *
   * @see ServiceInfoSnapshotConfigureEvent
   */
  public void publishServiceInfoUpdate() {
    this.publishServiceInfoUpdate(this.createServiceInfoSnapshot());
  }

  public void publishServiceInfoUpdate(@NotNull ServiceInfoSnapshot serviceInfoSnapshot) {
    // add configuration stuff when updating the current service snapshot
    if (this.currentServiceInfoSnapshot.getServiceId().equals(serviceInfoSnapshot.getServiceId())) {
      this.configureServiceInfoSnapshot(serviceInfoSnapshot);
    }

    // send the update to all nodes and services
    ChannelMessage.builder()
      .targetAll()
      .message("update_service_information")
      .channel(NetworkConstants.INTERNAL_MSG_CHANNEL)
      .buffer(DataBuf.empty().writeObject(serviceInfoSnapshot))
      .build()
      .send();
  }

  /**
   * Removes all PacketListeners from all channels of the Network Connctor from a specific ClassLoader. It is
   * recommended to do this with the disables of your own plugin
   *
   * @param classLoader the ClassLoader from which the IPacketListener implementations derive.
   */
  public void unregisterPacketListenersByClassLoader(@NotNull ClassLoader classLoader) {
    this.networkClient.getPacketRegistry().removeListeners(classLoader);

    for (INetworkChannel channel : this.networkClient.getChannels()) {
      channel.getPacketRegistry().removeListeners(classLoader);
    }
  }

  private void connectToNode() throws InterruptedException {
    Lock lock = new ReentrantLock();

    try {
      // acquire the lock on the current thread
      lock.lock();
      // create a new condition and the auth listener
      Condition condition = lock.newCondition();
      PacketAuthorizationResponseListener listener = new PacketAuthorizationResponseListener(lock, condition);
      // register the listener to the packet registry and connect to the target listener
      this.networkClient.getPacketRegistry().addListener(NetworkConstants.INTERNAL_AUTHORIZATION_CHANNEL, listener);
      this.networkClient.connect(this.config.getTargetListener());

      // wait for the authentication response
      boolean wasDone = condition.await(30, TimeUnit.SECONDS);
      // check if the auth was successful - explode if not
      if (!wasDone || !listener.wasAuthSuccessful()) {
        throw new IllegalStateException("Unable to authorize wrapper with node");
      }

      // remove the auth listener
      this.networkClient.getPacketRegistry().removeListener(NetworkConstants.INTERNAL_AUTHORIZATION_CHANNEL);
    } finally {
      lock.unlock();
    }
  }

  private boolean startApplication() throws Exception {
    return this.startApplication(this.commandLineArguments.remove(0));
  }

  private boolean startApplication(@NotNull String mainClass) throws Exception {
    Class<?> main = Class.forName(mainClass);
    Method method = main.getMethod("main", String[].class);

    Collection<String> arguments = new ArrayList<>(this.commandLineArguments);

    this.eventManager.callEvent(new ApplicationPreStartEvent(this, main, arguments));

    try {
      // checking if the application will be launched via the Minecraft LaunchWrapper
      Class.forName("net.minecraft.launchwrapper.Launch");

      // adds a tweak class to the LaunchWrapper which will prevent doubled loading of the CloudNet classes
      arguments.add("--tweakClass");
      arguments.add("de.dytanic.cloudnet.wrapper.tweak.CloudNetTweaker");
    } catch (ClassNotFoundException ignored) {
      // the LaunchWrapper is not available, doing nothing
    }

    Thread applicationThread = new Thread(() -> {
      try {
        LOGGER.info(String.format("Starting Application-Thread based of %s",
          this.getServiceConfiguration().getProcessConfig().getEnvironment()));
        method.invoke(null, new Object[]{arguments.toArray(new String[0])});
      } catch (Exception exception) {
        LOGGER.severe("Exception while starting application", exception);
      }
    }, "Application-Thread");
    applicationThread.setContextClassLoader(ClassLoader.getSystemClassLoader());
    applicationThread.start();

    this.eventManager.callEvent(
      new ApplicationPostStartEvent(this, main, applicationThread, ClassLoader.getSystemClassLoader()));
    return true;
  }

  public @NotNull IWrapperConfiguration getConfig() {
    return this.config;
  }

  public @NotNull Path getWorkingDirectoryPath() {
    return WORKING_DIRECTORY;
  }

  @UnmodifiableView
  public @NotNull List<String> getCommandLineArguments() {
    return Collections.unmodifiableList(this.commandLineArguments);
  }

  public @NotNull Thread getMainThread() {
    return this.mainThread;
  }

  public @NotNull ServiceInfoSnapshot getLastServiceInfoSnapShot() {
    return this.lastServiceInfoSnapShot;
  }

  public @NotNull ServiceInfoSnapshot getCurrentServiceInfoSnapshot() {
    return this.currentServiceInfoSnapshot;
  }
}