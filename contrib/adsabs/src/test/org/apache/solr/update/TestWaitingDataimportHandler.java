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

package org.apache.solr.update;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.channels.IllegalSelectorException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import monty.solr.util.MontySolrSetup;

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.dataimport.DataImportHandlerException;
import org.apache.solr.handler.dataimport.FailSafeInvenioNoRollbackWriter;
import org.apache.solr.handler.dataimport.NoRollbackWriter;
import org.apache.solr.handler.dataimport.SolrWriter;
import org.apache.solr.handler.dataimport.WaitingDataImportHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.util.AbstractSolrTestCase;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * Tests that the dataimport handler does really wait and does not
 * return immediately. Also, one of the fields is fetched by Python.
 * 
 */
public class TestWaitingDataimportHandler extends AbstractSolrTestCase {
	
	
  @BeforeClass
  public static void beforeTestWaitingDataimportHandler() throws Exception {
    System.setProperty("montysolr.home", MontySolrSetup.getMontySolrHome());
  }
  
  @AfterClass
  public static void afterTestWaitingDataimportHandler() throws Exception {
    System.clearProperty("montysolr.home");
  }
  
	public String getSchemaFile() {
		return MontySolrSetup.getMontySolrHome()
		+ "/contrib/adsabs/src/test-files/solr/collection1/conf/schema-minimal.xml";
	}

	public String getSolrConfigFile() {
		return MontySolrSetup.getMontySolrHome()
		+ "/contrib/adsabs/src/test-files/solr/collection1/conf/solrconfig-invenio-keeprecid-updater.xml";
	}

	public String getSolrHome() {
	  System.clearProperty("solr.solr.home"); //always force field re-computation
		return MontySolrSetup.getSolrHome() + "/example/solr";
	}
	

	
	public void testImport() throws Exception {
		
		
		String testDir = MontySolrSetup.getMontySolrHome() + "/src/test-files/";
		
		WaitingDataImportHandler handler = (WaitingDataImportHandler) h.getCore().getRequestHandler("/waiting-dataimport");
		
		SolrCore core = h.getCore();
		
		//System.err.println(MontySolrSetup.getMontySolrHome() + "/contrib/invenio/src/test-files/data/demo-site.xml");
		
		SolrQueryRequest req = req("command", "full-import",
				"dirs", testDir,
				"commit", "true",
				"url", "file://" + MontySolrSetup.getMontySolrHome() + "/contrib/adsabs/src/test-files/invenio-demo-site.xml"
				);
		SolrQueryResponse rsp = new SolrQueryResponse();
		
		core.execute(handler, req, rsp);
		
		//System.err.println(" ======= Calling commit ========");
		
		assertU(commit());
		
		assertQ(req("q", "*:*"), "//*[@numFound='104']");
		assertQ(req("q", "id:9"), "//*[@numFound='1']");
		assertQ(req("q", "id:19"), "//*[@numFound='1']");
		assertQ(req("q", "id:29"), "//*[@numFound='1']");
		assertQ(req("q", "id:104"), "//*[@numFound='1']");
		assertQ(req("q", "id:80"), "//*[@numFound='1']");
		
		
		failThis.put("9", true);
    failThis.put("19", true);
    failThis.put("29", true);
    failThis.put("104", true);
    failThis.put("80", true);
		
		// clean the slate
		assertU(delQ("*:*"));
		assertU(commit());
		assertQ(req("q", "*:*"), "//*[@numFound='0']");
		
		req = req("command", "full-import",
        "dirs", testDir,
        "commit", "true",
        "writerImpl", TestFailingWriter.class.getName(),
        "url", "file://" + MontySolrSetup.getMontySolrHome() + "/contrib/adsabs/src/test-files/invenio-demo-site.xml?p=" 
          + URLEncoder.encode("recid:1->60 OR recid:61->104", "UTF-8")
        );
    rsp = new SolrQueryResponse();
    core.execute(handler, req, rsp);
    
    
    assertQ(req("qt", "/invenio-doctor", "command", "info"), 
        "//str[@name='queueSize'][.='3']",
        "//str[@name='failedRecs'][.='0']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='0']",
        "//str[@name='registeredRequests'][.='3']",
        "//str[@name='restartedRequests'][.='0']",
        "//str[@name='docsToCheck'][.='96']",
        "//str[@name='status'][.='idle']"
        );
    assertQ(req("qt", "/invenio-doctor", "command", "detailed-info"), 
        "//str[@name='queueSize'][.='3']",
        "//str[@name='failedRecs'][.='0']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='0']",
        "//str[@name='registeredRequests'][.='3']",
        "//str[@name='restartedRequests'][.='0']",
        "//str[@name='docsToCheck'][.='96']",
        "//str[@name='status'][.='idle']",
        "*[count(//arr[@name='toBeDone']/str)=3]",
        "*[count(//arr[@name='failedBatches']/str)=0]"
        );
    
    
    InvenioDoctor controller = (InvenioDoctor) h.getCore().getRequestHandler("/invenio-doctor");
    req = req("command", "start");
    rsp = new SolrQueryResponse();
    core.execute(controller, req, rsp);
    
    while (controller.isBusy()) {
      Thread.sleep(300);
    }
    
    assertQ(req("qt", "/invenio-doctor", "command", "info"), 
        "//str[@name='status'][.='idle']"
        );
    
    assertQ(req("q", "*:*"), "//*[@numFound='99']");
    assertQ(req("q", "id:9"), "//*[@numFound='0']");
    assertQ(req("q", "id:19"), "//*[@numFound='0']");
    assertQ(req("q", "id:29"), "//*[@numFound='0']");
    assertQ(req("q", "id:104"), "//*[@numFound='0']");
    assertQ(req("q", "id:80"), "//*[@numFound='0']");
    assertQ(req("q", "id:10"), "//*[@numFound='1']");
    assertQ(req("q", "id:20"), "//*[@numFound='1']");
    assertQ(req("q", "id:30"), "//*[@numFound='1']");
    assertQ(req("q", "id:103"), "//*[@numFound='1']");
    assertQ(req("q", "id:81"), "//*[@numFound='1']");
    
    
    String response = h.query("/invenio-doctor", req("qt", "/invenio-doctor", "command", "detailed-info"));
    
    //System.out.println(response);
    
    assertQ(req("qt", "/invenio-doctor", "command", "detailed-info"), 
        "//str[@name='queueSize'][.='0']",
        "//str[@name='failedRecs'][.='5']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='5']",
        "//str[@name='registeredRequests'][.='12']",
        "//str[@name='restartedRequests'][.='12']",
        "//str[@name='docsToCheck'][.='0']",
        "//str[@name='status'][.='idle']"
        );
    
    
    req = req("command", "full-import",
        "dirs", testDir,
        "commit", "true",
        "writerImpl", TestFailingWriter.class.getName(),
        "url", "file://" + MontySolrSetup.getMontySolrHome() + "/contrib/invenio/src/test-files/data/non-existing-demo-site.xml?p="
            + URLEncoder.encode("recid:105->110", "UTF-8")
        );
    rsp = new SolrQueryResponse();
    core.execute(handler, req, rsp);
    
    req = req("command", "start");
    rsp = new SolrQueryResponse();
    core.execute(controller, req, rsp);
    
    while (controller.isBusy()) {
      Thread.sleep(300);
    }
		
    // One problem with our approach is that we cannot recognize a batch
    // that is completely wrong
    assertQ(req("qt", "/invenio-doctor", "command", "info"), 
        "//str[@name='queueSize'][.='0']",
        "//str[@name='failedRecs'][.='11']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='11']",
        "//str[@name='registeredRequests'][.='20']",
        "//str[@name='restartedRequests'][.='20']",
        "//str[@name='docsToCheck'][.='0']",
        "//str[@name='status'][.='idle']"
        );
    
    assertQ(req("qt", "/invenio-doctor", "command", "reset"));
    
    assertQ(req("qt", "/invenio-doctor", "command", "info"), 
        "//str[@name='queueSize'][.='0']",
        "//str[@name='failedRecs'][.='0']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='0']",
        "//str[@name='registeredRequests'][.='0']",
        "//str[@name='restartedRequests'][.='0']",
        "//str[@name='docsToCheck'][.='0']",
        "//str[@name='status'][.='idle']"
        );
    
    // now check that if the writerImpl is not set (and it is not inside solrconfig.xml)
    // the the logging writer has no effect
    
    req = req("command", "full-import",
        "dirs", testDir,
        "commit", "true",
        "url", "file://" + MontySolrSetup.getMontySolrHome() + "/contrib/invenio/src/test-files/data/non-existing-demo-site.xml?p="
           + URLEncoder.encode("recid:105->110", "UTF-8")
        );
    rsp = new SolrQueryResponse();
    core.execute(handler, req, rsp);
    
    req = req("command", "start");
    rsp = new SolrQueryResponse();
    core.execute(controller, req, rsp);
    
    while (controller.isBusy()) {
      Thread.sleep(300);
    }
    
    assertQ(req("qt", "/invenio-doctor", "command", "info"), 
        "//str[@name='queueSize'][.='0']",
        "//str[@name='failedRecs'][.='0']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='0']",
        "//str[@name='registeredRequests'][.='0']",
        "//str[@name='restartedRequests'][.='0']",
        "//str[@name='docsToCheck'][.='0']",
        "//str[@name='status'][.='idle']"
        );

    
    
    // reset and try new
    alreadyProcessed.clear();
    alreadyFailed.clear();
    
    req = req("command", "full-import",
        "dirs", testDir,
        "commit", "true",
        "writerImpl", TestFailingWriter.class.getName(),
        "url", "file://" + MontySolrSetup.getMontySolrHome() + "/contrib/adsabs/src/test-files/invenio-demo-site.xml?p=" 
        + URLEncoder.encode("recid:9 OR recid:99", "UTF-8")
        );
    rsp = new SolrQueryResponse();
    core.execute(handler, req, rsp);
    
    req = req("command", "full-import",
        "dirs", testDir,
        "commit", "true",
        "writerImpl", TestFailingWriter.class.getName(),
        "url", "file://" + MontySolrSetup.getMontySolrHome() + "/contrib/adsabs/src/test-files/invenio-demo-site.xml?p=" 
        + URLEncoder.encode("recid:9 OR recid:19", "UTF-8")
        );
    rsp = new SolrQueryResponse();
    core.execute(handler, req, rsp);
    
    req = req("command", "start");
    rsp = new SolrQueryResponse();
    core.execute(controller, req, rsp);
    
    while (controller.isBusy()) {
      assertQ(req("qt", "/invenio-doctor", "command", "info"), 
          "//str[@name='status'][.='busy']"
          );
      Thread.sleep(300);
    }
    
    // three requests because one part is common to both queries
    assertQ(req("qt", "/invenio-doctor", "command", "info"), 
        "//str[@name='queueSize'][.='0']",
        "//str[@name='failedRecs'][.='2']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='2']",
        "//str[@name='registeredRequests'][.='3']",
        "//str[@name='restartedRequests'][.='3']",
        "//str[@name='docsToCheck'][.='0']",
        "//str[@name='status'][.='idle']"
        );
    
    
    // reset and try new with url-encoded params
    alreadyProcessed.clear();
    alreadyFailed.clear();
    
    req = req("command", "full-import",
        "dirs", testDir,
        "commit", "true",
        "writerImpl", TestFailingWriter.class.getName(),
        "url", "file://" + MontySolrSetup.getMontySolrHome() 
                + "/contrib/adsabs/src/test-files/invenio-demo-site.xml?p=" 
                + URLEncoder.encode("recid:9->50 OR recid:60->99", "UTF-8")
        );
    rsp = new SolrQueryResponse();
    core.execute(handler, req, rsp);
    
    
    req = req("command", "start");
    rsp = new SolrQueryResponse();
    core.execute(controller, req, rsp);
    
    while (controller.isBusy()) {
      Thread.sleep(300);
    }
    // docs are counted unique
    assertQ(req("qt", "/invenio-doctor", "command", "info"), 
        "//str[@name='queueSize'][.='0']",
        "//str[@name='failedRecs'][.='4']",
        "//str[@name='failedBatches'][.='0']",
        "//str[@name='failedTotal'][.='4']",
        "//str[@name='registeredRequests'][.='15']",
        "//str[@name='restartedRequests'][.='15']",
        "//str[@name='docsToCheck'][.='0']",
        "//str[@name='status'][.='idle']"
        );
	}
	
	
	public static final Map<String, Boolean> alreadyProcessed = new HashMap<String, Boolean>();
	public static final Map<String, Integer> alreadyFailed = new HashMap<String, Integer>();
	public static final Map<String, Boolean> failThis = new HashMap<String, Boolean>();
	
	public static class TestFailingWriter extends FailSafeInvenioNoRollbackWriter {
	  
	  List<Integer> allowedIds = null;
	  
    public TestFailingWriter(UpdateRequestProcessor processor,
        SolrQueryRequest req) {
      super(processor, req);
    }
	  
    @Override
    public void rollback() {
      // hack, to make the testing possible (failed recs that are out of the current range, should be removed)
//      List<Integer> range = getRange();
//      for (Entry<String, Integer> e: alreadyFailed.entrySet()) {
//        int v = Integer.parseInt(e.getKey());
//        if (!(range.indexOf(v) > -1) && this.processedIds.contains(e.getKey())) {
//          this.processedIds.remove((int) this.processedIds.indexOf(e.getKey()));
//        }
//      }
      super.rollback();
    }
    
    @Override
    public boolean upload(SolrInputDocument d) {
      SolrInputField f = d.getField("id");
      String val = (String) f.getFirstValue();
      
      if (!getRange().contains((int) Integer.parseInt(val))) return false;
      
      if (alreadyProcessed.containsKey(val)) {
        return false;
      }
      if (failThis.containsKey(val)) {
        if (!alreadyFailed.containsKey(val)) {
          alreadyFailed.put(val, 0);
        }
        alreadyFailed.put(val, alreadyFailed.get(val)+1);
        //if (alreadyFailed.get(val) > 2) return false; // we fail only twice, then we skip
        throw new IllegalStateException("Causing rollback to be called! Id: " + val);
      }
      alreadyProcessed.put(val, true);
      return super.upload(d);
    }
    
    private List<Integer> getRange() {
      if (allowedIds!= null) return allowedIds;
      
      SolrQueryRequest r = getReq();
      String v = r.getParams().get("url").split("p=")[1];
      try {
        v = URLDecoder.decode(v, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new IllegalSelectorException();
      }
      ArrayList<Integer> out = new ArrayList<Integer>();
      for (String s: v.split(" OR ")) {
        s = s.replace("recid:", "");
        if (s.indexOf("->") > -1) {
          String[] range = s.split("->");
          int max = Integer.parseInt(range[1]);
          for (int i=Integer.parseInt(range[0]);i<=max;i++ ) {
            out.add(i);
          }
        }
        else {
          out.add(Integer.parseInt(s));
        }
      }
      allowedIds = out;
      return out;
    }
	}
	// Uniquely for Junit 3
	public static junit.framework.Test suite() {
        return new junit.framework.JUnit4TestAdapter(TestWaitingDataimportHandler.class);
    }
}
