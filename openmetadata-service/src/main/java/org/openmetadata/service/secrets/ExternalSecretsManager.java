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

import org.openmetadata.schema.entity.services.ServiceType;
import org.openmetadata.schema.services.connections.metadata.SecretsManagerProvider;

public abstract class ExternalSecretsManager extends SecretsManager {
  public static final String NULL_SECRET_STRING = "null";

  protected ExternalSecretsManager(SecretsManagerProvider secretsManagerProvider, String clusterPrefix) {
    super(secretsManagerProvider, clusterPrefix);
  }

  @Override
  public Object encryptOrDecryptServiceConnectionConfig(
      Object connectionConfig, String connectionType, String connectionName, ServiceType serviceType, boolean encrypt) {
    return connectionConfig;
  }

  public void upsertSecret(String secretName, String secretValue) {
    if (existSecret(secretName)) {
      updateSecret(secretName, secretValue != null ? secretValue : NULL_SECRET_STRING);
    } else {
      storeSecret(secretName, secretValue != null ? secretValue : NULL_SECRET_STRING);
    }
  }

  public boolean existSecret(String secretName) {
    try {
      return getSecret(secretName) != null;
    } catch (Exception e) {
      return false;
    }
  }

  abstract void storeSecret(String secretName, String secretValue);

  abstract void updateSecret(String secretName, String secretValue);

  abstract String getSecret(String secretName);
}
