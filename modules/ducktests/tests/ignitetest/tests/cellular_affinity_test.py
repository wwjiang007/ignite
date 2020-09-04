# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
This module contains Cellular Affinity tests.
"""

from ducktape.mark.resource import cluster
from jinja2 import Template

from ignitetest.services.ignite import IgniteService
from ignitetest.services.ignite_app import IgniteApplicationService
from ignitetest.services.utils.ignite_configuration import IgniteConfiguration, IgniteClientConfiguration
from ignitetest.services.utils.ignite_configuration.discovery import from_ignite_cluster
from ignitetest.utils import ignite_versions, version_if
from ignitetest.utils.ignite_test import IgniteTest
from ignitetest.utils.version import DEV_BRANCH, IgniteVersion


# pylint: disable=W0223
class CellularAffinity(IgniteTest):
    """
    Tests Cellular Affinity scenarios.
    """
    NUM_NODES = 3

    ATTRIBUTE = "CELL"

    CACHE_NAME = "test-cache"

    CONFIG_TEMPLATE = """
            <property name="cacheConfiguration">
                <list>
                    <bean class="org.apache.ignite.configuration.CacheConfiguration">
                        <property name="affinity">
                            <bean class="org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction">
                                <property name="affinityBackupFilter">
                                    <bean class="org.apache.ignite.internal.ducktest.tests.cellular_affinity_test.CellularAffinityBackupFilter">
                                        <constructor-arg value="{{ attr }}"/>
                                    </bean>
                                </property>
                            </bean>
                        </property>
                        <property name="name" value="{{ cacheName }}"/>
                        <property name="backups" value="{{ nodes }}"/> 
                    </bean>
                </list>
            </property>
        """

    @staticmethod
    def properties():
        """
        :return: Configuration properties.
        """
        return Template(CellularAffinity.CONFIG_TEMPLATE) \
            .render(nodes=CellularAffinity.NUM_NODES,  # bigger than cell capacity (to handle single cell useless test)
                    attr=CellularAffinity.ATTRIBUTE,
                    cacheName=CellularAffinity.CACHE_NAME)

    @cluster(num_nodes=NUM_NODES * 3 + 1)
    @version_if(lambda version: version >= DEV_BRANCH)
    @ignite_versions(str(DEV_BRANCH))
    def test(self, ignite_version):
        """
        Test Cellular Affinity scenario (partition distribution).
        """
        cell1 = self.start_cell(ignite_version, ['-D' + CellularAffinity.ATTRIBUTE + '=1'])
        self.start_cell(ignite_version, ['-D' + CellularAffinity.ATTRIBUTE + '=2'], joined_cluster=cell1)
        self.start_cell(ignite_version, ['-D' + CellularAffinity.ATTRIBUTE + '=XXX', '-DRANDOM=42'],
                        joined_cluster=cell1)

        checker = IgniteApplicationService(
            self.test_context,
            IgniteClientConfiguration(version=IgniteVersion(ignite_version), discovery_spi=from_ignite_cluster(cell1)),
            java_class_name="org.apache.ignite.internal.ducktest.tests.cellular_affinity_test.DistributionChecker",
            params={"cacheName": CellularAffinity.CACHE_NAME,
                    "attr": CellularAffinity.ATTRIBUTE,
                    "nodesPerCell": self.NUM_NODES})

        checker.run()

    def start_cell(self, version, jvm_opts, joined_cluster=None):
        """
        Starts cell.
        """
        config = IgniteConfiguration(version=IgniteVersion(version), properties=self.properties())
        if joined_cluster:
            config = config._replace(discovery_spi=from_ignite_cluster(joined_cluster))

        ignites = IgniteService(self.test_context, config, num_nodes=CellularAffinity.NUM_NODES, jvm_opts=jvm_opts)

        ignites.start()

        return ignites