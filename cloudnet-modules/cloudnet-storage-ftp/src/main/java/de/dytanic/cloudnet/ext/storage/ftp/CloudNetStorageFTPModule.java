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

package de.dytanic.cloudnet.ext.storage.ftp;

import com.google.gson.JsonParseException;
import de.dytanic.cloudnet.common.document.gson.JsonDocument;
import de.dytanic.cloudnet.common.log.LogManager;
import de.dytanic.cloudnet.common.log.Logger;
import de.dytanic.cloudnet.driver.module.ModuleLifeCycle;
import de.dytanic.cloudnet.driver.module.ModuleTask;
import de.dytanic.cloudnet.driver.network.HostAndPort;
import de.dytanic.cloudnet.ext.storage.ftp.client.FTPCredentials;
import de.dytanic.cloudnet.ext.storage.ftp.client.FTPType;
import de.dytanic.cloudnet.ext.storage.ftp.storage.queue.FTPQueueStorage;
import de.dytanic.cloudnet.module.NodeCloudNetModule;
import java.util.Arrays;

public final class CloudNetStorageFTPModule extends NodeCloudNetModule {

  private static final Logger LOGGER = LogManager.getLogger(CloudNetStorageFTPModule.class);

  private FTPQueueStorage templateStorage;

  @ModuleTask(order = 127, event = ModuleLifeCycle.STARTED)
  public void initConfiguration() {

    JsonDocument configuration;
    try {
      configuration = super.getConfigExceptionally();
    } catch (Exception exception) {
      throw new JsonParseException("Exception while parsing storage-ftp configuration. Your configuration is invalid.");
    }

    if (configuration.contains("ssl")) {
      configuration.remove("ssl");
      configuration.remove("bufferSize");
    }

    configuration.get("type", FTPType.class, FTPType.FTP);
    configuration.get("address", HostAndPort.class, new HostAndPort("127.0.0.1", 21));

    configuration.getString("storage", "ftp");
    configuration.getString("username", "root");
    configuration.getString("password", "password");

    configuration.getString("sshKeyPath", "");
    configuration.getString("sshKeyPassword", "");

    configuration.getString("baseDirectory", "/home/cloudnet");

    super.saveConfig();
  }

  @ModuleTask(order = 126, event = ModuleLifeCycle.STARTED)
  public void registerStorage() {
    FTPType ftpType = super.getConfig().get("type", FTPType.class);

    if (ftpType == null) {
      super.getModuleWrapper().stopModule();

      throw new IllegalArgumentException("Invalid ftp type! Available types: " + Arrays.toString(FTPType.values()));
    }

    String storageName = super.getConfig().getString("storage");
    FTPCredentials credentials = super.getConfig().toInstanceOf(FTPCredentials.class);

    this.templateStorage = new FTPQueueStorage(ftpType.createNewTemplateStorage(storageName, credentials));
    super.registerTemplateStorage(storageName, this.templateStorage);

    Thread ftpQueueThread = new Thread(this.templateStorage, "FTP queue worker");
    ftpQueueThread.setDaemon(true);
    ftpQueueThread.start();
  }

  @ModuleTask(event = ModuleLifeCycle.STOPPED)
  public void unregisterStorage() {
    if (this.templateStorage != null) {
      try {
        this.templateStorage.close();
      } catch (Exception exception) {
        LOGGER.severe("Exception while closing template storage", exception);
      }
    }
  }
}
