/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Mariana Cedica <mcedica@nuxeo.com>
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.elasticsearch;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.junit.runner.RunWith;
import org.nuxeo.drive.service.AbstractChangeFinderTestCase;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.elasticsearch.ElasticSearchConstants;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.test.RepositoryElasticSearchFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;

/**
 * Test the {@link ESAuditChangeFinder}.
 *
 * @since 7.3
 */
@RunWith(FeaturesRunner.class)
@Features({ PlatformFeature.class, RepositoryElasticSearchFeature.class })
@Deploy({ "org.nuxeo.ecm.platform.audit", "org.nuxeo.ecm.platform.uidgen.core", "org.nuxeo.elasticsearch.seqgen",
        "org.nuxeo.elasticsearch.seqgen.test:elasticsearch-seqgen-index-test-contrib.xml",
        "org.nuxeo.elasticsearch.audit",
        "org.nuxeo.elasticsearch.audit.test:elasticsearch-audit-index-test-contrib.xml",
        "org.nuxeo.drive.elasticsearch" })
@LocalDeploy("org.nuxeo.drive.elasticsearch:OSGI-INF/test-nuxeodrive-elasticsearch-contrib.xml")
public class TestESAuditChangeFinder extends AbstractChangeFinderTestCase {

    @Inject
    protected ElasticSearchAdmin esa;

    @Override
    protected void waitForAsyncCompletion() throws Exception {
        super.waitForAsyncCompletion();
        // Wait for indexing
        esa.prepareWaitForIndexing().get(20, TimeUnit.SECONDS);
        // Explicit refresh
        esa.refresh();
        // Explicit refresh for the audit index until it is handled by esa.refresh
        esa.getClient().admin().indices().prepareRefresh(esa.getIndexNameForType(ElasticSearchConstants.ENTRY_TYPE)).get();
    }

    @Override
    protected void cleanUpAuditLog() {
        esa.dropAndInitIndex(esa.getIndexNameForType(ElasticSearchConstants.ENTRY_TYPE));
    }

}