/*
 *  Copyright 2022 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.secrets;

import static java.util.Objects.isNull;

import java.util.List;
import java.util.Locale;
import lombok.Getter;
import org.openmetadata.schema.entity.services.ServiceType;
import org.openmetadata.schema.services.connections.metadata.SecretsManagerProvider;
import org.openmetadata.service.exception.InvalidServiceConnectionException;
import org.openmetadata.service.exception.SecretsManagerException;
import org.openmetadata.service.util.JsonUtils;

public abstract class SecretsManager {

  @Getter private final String clusterPrefix;

  @Getter private final SecretsManagerProvider secretsManagerProvider;

  protected SecretsManager(SecretsManagerProvider secretsManagerProvider, String clusterPrefix) {
    this.secretsManagerProvider = secretsManagerProvider;
    this.clusterPrefix = clusterPrefix;
  }

  public abstract Object encryptOrDecryptServiceConnectionConfig(
      Object connectionConfig, String connectionType, String connectionName, ServiceType serviceType, boolean encrypt);

  protected String getSecretSeparator() {
    return "/";
  }

  protected boolean startsWithSeparator() {
    return true;
  }

  protected String buildSecretId(String... secretIdValues) {
    StringBuilder format = new StringBuilder();
    format.append(startsWithSeparator() ? getSecretSeparator() : "");
    format.append(clusterPrefix);
    for (String secretIdValue : List.of(secretIdValues)) {
      if (isNull(secretIdValue)) {
        throw new SecretsManagerException("Cannot build a secret id with null values.");
      }
      format.append(getSecretSeparator());
      format.append("%s");
    }
    return String.format(format.toString(), (Object[]) secretIdValues).toLowerCase();
  }

  protected Class<?> createConnectionConfigClass(String connectionType, String connectionPackage)
      throws ClassNotFoundException {
    String clazzName =
        "org.openmetadata.schema.services.connections." + connectionPackage + "." + connectionType + "Connection";
    return Class.forName(clazzName);
  }

  protected String extractConnectionPackageName(ServiceType serviceType) {
    // All package names must be lowercase per java naming convention
    return serviceType.value().toLowerCase(Locale.ROOT);
  }

  public void validateServiceConnection(Object connectionConfig, String connectionType, ServiceType serviceType) {
    try {
      Class<?> clazz = createConnectionConfigClass(connectionType, extractConnectionPackageName(serviceType));
      JsonUtils.readValue(JsonUtils.pojoToJson(connectionConfig), clazz);
    } catch (Exception exception) {
      throw InvalidServiceConnectionException.byMessage(
          connectionType, String.format("Failed to construct connection instance of %s", connectionType));
    }
  }
}
