/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.ignite.internal.processors.query.calcite.integration;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.internal.processors.cache.query.GridCacheQueryType;
import org.apache.ignite.internal.processors.performancestatistics.AbstractPerformanceStatisticsTest;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.junit.Test;

import static org.apache.ignite.internal.processors.cache.query.GridCacheQueryType.SQL_FIELDS;
import static org.apache.ignite.internal.processors.performancestatistics.AbstractPerformanceStatisticsTest.cleanPerformanceStatisticsDir;
import static org.apache.ignite.internal.processors.performancestatistics.AbstractPerformanceStatisticsTest.startCollectStatistics;
import static org.apache.ignite.internal.processors.performancestatistics.AbstractPerformanceStatisticsTest.stopCollectStatisticsAndRead;

/**
 * Test SQL diagnostic tools.
 */
public class SqlDiagnosticIntegrationTest extends AbstractBasicIntegrationTest {
    /** */
    @Override protected int nodeCount() {
        return 2;
    }

    /** */
    @Test
    public void testPerformanceStatistics() throws Exception {
        cleanPerformanceStatisticsDir();
        startCollectStatistics();

        long startTime = U.currentTimeMillis();

        sql(grid(0), "SELECT * FROM table(system_range(1, 1000))");
        sql(grid(0), "CREATE TABLE test_perf_stat (a INT)");
        sql(grid(0), "INSERT INTO test_perf_stat VALUES (0), (1), (2), (3), (4)");
        sql(grid(0), "SELECT * FROM test_perf_stat");

        // Only the last query should trigger queryReads event.
        // The first query uses generated data and doesn't require any page reads.
        // The second query is DDL and doesn't perform any page reads as well.
        // The third query performs scan for static values and insert data into cache. We are able to analyze only
        // ScanNode page reads, since table/index scans are local and executed in current thread. ModifyNode uses
        // distributed `invoke` operation, which can be executed by other threads or on other nodes. It's hard to
        // obtain correct value of page reads for these types of operations, so, currently we just ignore page reads
        // performed by ModifyNode. Despite static values scan themself doesn't require any page reads, it still can
        // catch some page reads performed by insert operation. But, taking into account small amount of inserted
        // values, it's not enough rows to trigger batch insert during values scan, and we expect zero page-reads
        // for this query in this test.
        // The fourth query is a table scan and should perform page reads on all data nodes.

        AtomicInteger qryCnt = new AtomicInteger();
        AtomicInteger readsCnt = new AtomicInteger();
        Iterator<String> sqlIt = F.asList("SELECT", "CREATE", "INSERT", "SELECT").iterator();
        Set<UUID> dataNodesIds = new HashSet<>(F.asList(grid(0).localNode().id(), grid(1).localNode().id()));
        Set<UUID> readsNodes = new HashSet<>(dataNodesIds);
        Set<Long> readsQueries = new HashSet<>();
        AtomicLong lastQryId = new AtomicLong();

        stopCollectStatisticsAndRead(new AbstractPerformanceStatisticsTest.TestHandler() {
            @Override public void query(
                UUID nodeId,
                GridCacheQueryType type,
                String text,
                long id,
                long qryStartTime,
                long duration,
                boolean success
            ) {
                qryCnt.incrementAndGet();

                assertTrue(nodeId.equals(grid(0).localNode().id()));
                assertEquals(SQL_FIELDS, type);
                assertTrue(text.startsWith(sqlIt.next()));
                assertTrue(qryStartTime >= startTime);
                assertTrue(duration >= 0);
                assertTrue(success);

                lastQryId.set(id);
            }

            @Override public void queryReads(
                UUID nodeId,
                GridCacheQueryType type,
                UUID qryNodeId,
                long id,
                long logicalReads,
                long physicalReads
            ) {
                readsCnt.incrementAndGet();

                readsQueries.add(id);
                assertTrue(dataNodesIds.contains(qryNodeId));
                readsNodes.remove(nodeId);

                assertTrue(grid(0).localNode().id().equals(qryNodeId));
                assertEquals(SQL_FIELDS, type);
                assertTrue(logicalReads > 0);
            }
        });

        assertEquals(4, qryCnt.get());
        assertTrue("Query reads expected on nodes: " + readsNodes, readsNodes.isEmpty());
        assertEquals(Collections.singleton(lastQryId.get()), readsQueries);
    }
}