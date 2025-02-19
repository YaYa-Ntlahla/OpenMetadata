#  Copyright 2022 Collate
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  http://www.apache.org/licenses/LICENSE-2.0
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

"""
Abstract class for AWS based secrets manager implementations
"""
from abc import ABC, abstractmethod
from typing import Optional

from metadata.clients.aws_client import AWSClient
from metadata.generated.schema.entity.services.connections.metadata.secretsManagerProvider import (
    SecretsManagerProvider,
)
from metadata.utils.logger import utils_logger
from metadata.utils.secrets.external_secrets_manager import ExternalSecretsManager

logger = utils_logger()

NULL_VALUE = "null"


class AWSBasedSecretsManager(ExternalSecretsManager, ABC):
    """
    AWS Secrets Manager class
    """

    def __init__(
        self,
        credentials: Optional["AWSCredentials"],
        client: str,
        provider: SecretsManagerProvider,
    ):
        super().__init__(provider)
        self.client = AWSClient(credentials).get_client(client)

    @abstractmethod
    def get_string_value(self, secret_id: str) -> str:
        """
        :param secret_id: The secret id to retrieve
        :return: The value of the secret
        """
