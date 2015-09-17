package org.apache.solr.search.function;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import monty.solr.util.MontySolrSetup;

import org.apache.solr.util.AbstractSolrTestCase;
import org.junit.BeforeClass;

/**
 *
 *
 **/
public class PositionSearchTest extends AbstractSolrTestCase {

	@BeforeClass
	public static void beforeClass() throws Exception {
		
		System.setProperty("solr.allow.unsafe.resourceloading", "true");
		schemaString = MontySolrSetup.getMontySolrHome()
				+ "/contrib/adsabs/src/test-files/solr/collection1/conf/schema-fieldpos.xml";
		
		configString = MontySolrSetup.getMontySolrHome()
				+ "/contrib/adsabs/src/test-files/solr/collection1/conf/solrconfig-fieldpos.xml";
		
		initCore(configString, schemaString, MontySolrSetup.getSolrHome() + "/example/solr");
	}
	
	
	
	public String getSolrHome() {
    System.clearProperty("solr.solr.home"); // always force recomputing the solr.home
    return MontySolrSetup.getSolrHome() + "/example/solr";
  }

	public void test() throws Exception {
		assertU(adoc("id", "1", "author_ws",
				"ellis luker brooks simko mele pele", "test_ws",
				"roman one two three"));
		assertU(adoc("id", "2", "author_ws", "this is not author list",
				"test_ws", "roman one two three"));
		assertU(adoc("id", "3", "author_ws",
				"ellis luker brooks simko mele pele winehouse"));
		assertU(adoc("id", "4", "author_ws",
				"hey bumping of author name positions is necessary!"));
		assertU(commit());

		assertQ(req("q", "test_ws:roman"), "//*[@numFound='2']");

		assertQ(req("q", "id:1"), "//*[@numFound='1']");

		assertQ(req("q", "author_ws:ellis"), "//*[@numFound='2']");

		assertQ(req("fl", "id,score", "q", "_val_:\"pos(author_ws,ellis, 0,3)\""),
				"//*[@numFound='4']",
				"//result/doc[1]/float[@name='id']='1.0'",
				"//result/doc[1]/float[@name='score']='1.0'",
				"//result/doc[2]/float[@name='id']='3.0'",
				"//result/doc[2]/float[@name='score']='1.0'",
				"//result/doc[3]/float[@name='score']='-1.0'");

		assertQ(req("fl", "id,score", "q", "_val_:\"pos(author_ws,winehouse, 3, 8)\""),
				"//*[@numFound='4']",
				"//result/doc[1]/float[@name='id']='3.0'",
				"//result/doc[1]/float[@name='score']='1.0'",
				"//result/doc[2]/float[@name='score']='-1.0'");

		assertQ(req("fl", "id,score", "q",
				"{!frange l=0 u=9999}pos(author_ws,ellis, 0, 1)"), // only first author
		"//*[@numFound='2']", "//result/doc[1]/float[@name='id']='1.0'",
				"//result/doc[2]/float[@name='id']='3.0'",
				"//result/doc[1]/float[@name='score']='1.0'",
				"//result/doc[2]/float[@name='score']='1.0'");

		assertQ(req("fl", "id,score", "q",
				"{!frange l=0 u=9999}pos(author_ws,luker, 1, 2)"), // only 2nd author
		"//*[@numFound='2']", "//result/doc[1]/float[@name='id']='1.0'",
				"//result/doc[2]/float[@name='id']='3.0'",
				"//result/doc[1]/float[@name='score']='1.0'",
				"//result/doc[2]/float[@name='score']='1.0'");
	}
	
	// Uniquely for Junit 3
	public static junit.framework.Test suite() {
        return new junit.framework.JUnit4TestAdapter(PositionSearchTest.class);
    }
}
