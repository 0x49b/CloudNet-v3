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

package de.dytanic.cloudnet.ext.cloudflare.listener;

import de.dytanic.cloudnet.common.language.I18n;
import de.dytanic.cloudnet.common.log.LogManager;
import de.dytanic.cloudnet.common.log.Logger;
import de.dytanic.cloudnet.driver.event.EventListener;
import de.dytanic.cloudnet.driver.service.ServiceLifeCycle;
import de.dytanic.cloudnet.event.service.CloudServicePostLifecycleEvent;
import de.dytanic.cloudnet.ext.cloudflare.CloudNetCloudflareModule;
import de.dytanic.cloudnet.ext.cloudflare.CloudflareConfigurationEntry;
import de.dytanic.cloudnet.ext.cloudflare.CloudflareGroupConfiguration;
import de.dytanic.cloudnet.ext.cloudflare.cloudflare.CloudFlareAPI;
import de.dytanic.cloudnet.ext.cloudflare.cloudflare.DnsRecordDetail;
import de.dytanic.cloudnet.ext.cloudflare.dns.SRVRecord;
import de.dytanic.cloudnet.service.ICloudService;
import java.util.function.BiConsumer;

public final class CloudflareStartAndStopListener {

  private static final Logger LOGGER = LogManager.getLogger(CloudflareStartAndStopListener.class);

  private final CloudFlareAPI cloudFlareAPI;

  public CloudflareStartAndStopListener(CloudFlareAPI cloudFlareAPI) {
    this.cloudFlareAPI = cloudFlareAPI;
  }

  @EventListener
  public void handlePostStart(CloudServicePostLifecycleEvent event) {
    if (event.getNewLifeCycle() != ServiceLifeCycle.RUNNING) {
      return;
    }

    this.handle0(event.getService(), (entry, configuration) -> {
      DnsRecordDetail recordDetail = this.cloudFlareAPI.createRecord(
        event.getService().getServiceId().getUniqueId(),
        entry,
        SRVRecord.forConfiguration(entry, configuration, event.getService().getServiceConfiguration().getPort())
      );

      if (recordDetail != null) {
        LOGGER
          .info(I18n.trans("module-cloudflare-create-dns-record-for-service")
            .replace("%service%", event.getService().getServiceId().getName())
            .replace("%domain%", entry.getDomainName())
            .replace("%recordId%", recordDetail.getId())
          );
      }
    });
  }

  @EventListener
  public void handlePostStop(CloudServicePostLifecycleEvent event) {
    if (event.getNewLifeCycle() != ServiceLifeCycle.STOPPED) {
      this.handle0(event.getService(), (entry, configuration) -> {
        for (DnsRecordDetail detail : this.cloudFlareAPI.deleteAllRecords(event.getService())) {
          LOGGER
            .info(I18n.trans("module-cloudflare-delete-dns-record-for-service")
              .replace("%service%", event.getService().getServiceId().getName())
              .replace("%domain%", entry.getDomainName())
              .replace("%recordId%", detail.getId())
            );
        }
      });
    }
  }

  private void handle0(ICloudService cloudService,
    BiConsumer<CloudflareConfigurationEntry, CloudflareGroupConfiguration> handler) {
    for (CloudflareConfigurationEntry entry : CloudNetCloudflareModule.getInstance().getCloudflareConfiguration()
      .getEntries()) {
      if (entry != null && entry.isEnabled() && entry.getGroups() != null && !entry.getGroups().isEmpty()) {
        for (CloudflareGroupConfiguration groupConfiguration : entry.getGroups()) {
          if (groupConfiguration != null
            && cloudService.getServiceConfiguration().getGroups().contains(groupConfiguration.getName())) {
            handler.accept(entry, groupConfiguration);
          }
        }
      }
    }
  }
}