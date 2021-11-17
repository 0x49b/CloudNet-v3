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

package eu.cloudnetservice.cloudnet.ext.syncproxy.platform.velocity;

import static eu.cloudnetservice.ext.adventure.AdventureSerializerUtil.serialize;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.proxy.server.ServerPing.SamplePlayer;
import com.velocitypowered.api.proxy.server.ServerPing.Version;
import eu.cloudnetservice.cloudnet.ext.syncproxy.config.SyncProxyLoginConfiguration;
import eu.cloudnetservice.cloudnet.ext.syncproxy.config.SyncProxyMotd;
import java.util.Arrays;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

public final class VelocitySyncProxyListener {

  private final VelocitySyncProxyManagement syncProxyManagement;

  public VelocitySyncProxyListener(@NotNull VelocitySyncProxyManagement syncProxyManagement) {
    this.syncProxyManagement = syncProxyManagement;
  }

  @Subscribe
  public void handleProxyPing(ProxyPingEvent event) {
    SyncProxyLoginConfiguration loginConfiguration = this.syncProxyManagement.getCurrentLoginConfiguration();

    SyncProxyMotd motd;
    if (loginConfiguration == null || (motd = this.syncProxyManagement.getRandomMotd()) == null) {
      return;
    }

    int onlinePlayers = this.syncProxyManagement.getOnlinePlayerCount();
    int maxPlayers;

    if (motd.isAutoSlot()) {
      maxPlayers = Math.min(loginConfiguration.getMaxPlayers(), onlinePlayers + motd.getAutoSlotMaxPlayersDistance());
    } else {
      maxPlayers = loginConfiguration.getMaxPlayers();
    }

    String protocolText = motd.format(motd.getProtocolText(), onlinePlayers, maxPlayers);
    Version version = event.getPing().getVersion();

    if (protocolText != null) {
      version = new Version(1, protocolText);
    }

    ServerPing.Builder builder = ServerPing.builder()
      .version(version)
      .onlinePlayers(onlinePlayers)
      .maximumPlayers(maxPlayers)
      .samplePlayers(motd.getPlayerInfo() != null ?
        Arrays.stream(motd.getPlayerInfo())
          .map(s -> new SamplePlayer(
            s.replace("&", "§"),
            UUID.randomUUID()
          )).toArray(SamplePlayer[]::new) : new SamplePlayer[0])
      .description(PlainTextComponentSerializer.plainText()
        .deserialize(motd.format(motd.getFirstLine() + "\n" + motd.getSecondLine(), onlinePlayers, maxPlayers)));

    event.getPing().getFavicon().ifPresent(builder::favicon);
    event.getPing().getModinfo().ifPresent(builder::mods);

    event.setPing(builder.build());

  }

  @Subscribe
  public void handlePlayerLogin(@NotNull LoginEvent event) {
    SyncProxyLoginConfiguration loginConfiguration = this.syncProxyManagement.getCurrentLoginConfiguration();
    if (loginConfiguration == null) {
      return;
    }

    Player player = event.getPlayer();

    if (loginConfiguration.isMaintenance()) {
      // the player is either whitelisted or has the permission to join during maintenance, ignore him
      if (this.syncProxyManagement.checkPlayerMaintenance(player)) {
        return;
      }
      Component reason = serialize(
        this.syncProxyManagement.getConfiguration().getMessage("player-login-not-whitelisted", null));
      event.setResult(ComponentResult.denied(reason));
      return;
    }
    if (this.syncProxyManagement.getOnlinePlayerCount() >= loginConfiguration.getMaxPlayers()
      && !player.hasPermission("cloudnet.syncproxy.fulljoin")) {
      Component reason = serialize(
        this.syncProxyManagement.getConfiguration().getMessage("player-login-full-server", null));
      event.setResult(ComponentResult.denied(reason));
    }
  }
}