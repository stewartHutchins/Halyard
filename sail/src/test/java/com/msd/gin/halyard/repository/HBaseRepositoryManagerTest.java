/*
 * Copyright 2019 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
 * Inc., Kenilworth, NJ, USA.
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
package com.msd.gin.halyard.repository;

import com.msd.gin.halyard.common.HBaseServerTestInstance;

import java.io.File;
import java.util.Collection;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.eclipse.rdf4j.repository.manager.RepositoryInfo;
import org.eclipse.rdf4j.repository.sail.config.SailRepositoryConfig;
import org.eclipse.rdf4j.sail.memory.config.MemoryStoreConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 *
 * @author Adam Sotona (MSD)
 */
public class HBaseRepositoryManagerTest {
	private HBaseRepositoryManager rm;

	@BeforeEach
	public void setup() throws Exception {
		File f = File.createTempFile("HBaseRepositoryManagerTest", "");
		f.delete();
		f.deleteOnExit();
		rm = new HBaseRepositoryManager(f, HBaseServerTestInstance.getInstanceConfig());
	}

	@AfterEach
	public void teardown() {
		rm.shutDown();
	}

	@Test
    public void testGetLocation() throws Exception {
		rm.getLocation();
    }

    @Test
    public void testHttpClient() {
        assertNull(rm.getHttpClient());
		HttpClient cl = HttpClientBuilder.create().build();
        rm.setHttpClient(cl);
        assertEquals(cl, rm.getHttpClient());
    }

    @Test
    public void testAddRepositoryPersists() throws Exception {
        rm.init();
		rm.addRepositoryConfig(new RepositoryConfig("repoTest", "Test repository", new SailRepositoryConfig(new MemoryStoreConfig(false))));
        assertTrue(rm.getAllRepositories().contains(rm.getRepository("repoTest")));
		teardown();
        //test persistence
		setup();
        rm.init();
        assertTrue(rm.getAllRepositories().contains(rm.getRepository("repoTest")));
    }

	@Test
	public void testGets() throws Exception {
		rm.init();
		Collection<String> ids = rm.getRepositoryIDs();
		Collection<RepositoryInfo> infos = rm.getAllRepositoryInfos();
		assertEquals(ids.size(), infos.size());
		for (String id : ids) {
			assertNotNull(rm.getRepositoryInfo(id));
		}
		Collection<Repository> repos = rm.getAllRepositories();
		assertEquals(ids.size(), repos.size());
		for (String id : ids) {
			assertNotNull(rm.getRepository(id));
		}
	}
}
