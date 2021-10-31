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

tasks.withType<Jar> {
  archiveFileName.set(Files.cloudperms)
}

dependencies {
  "compileOnly"(project(":cloudnet-wrapper-jvm"))
  "compileOnly"("cn.nukkit", "nukkit", Versions.nukkitX)
  "compileOnly"("org.spongepowered", "spongeapi", Versions.sponge)
  "compileOnly"("org.spigotmc", "spigot-api", Versions.spigotApi)
  "compileOnly"("net.md-5", "bungeecord-api", Versions.bungeecord)
  "compileOnly"("com.velocitypowered", "velocity-api", Versions.velocity)
  "compileOnly"("dev.waterdog.waterdogpe", "waterdog", Versions.waterdogpe)
}

/*

moduleJson {
  main = "de.dytanic.cloudnet.ext.cloudperms.node.CloudNetCloudPermissionsModule"
  author = "CloudNetService"
  description = "Node extension for the CloudNet node runtime, which include a player permission system"
}*/