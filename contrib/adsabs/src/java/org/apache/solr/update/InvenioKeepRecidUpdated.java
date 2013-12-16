package org.apache.solr.update;

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


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import monty.solr.jni.MontySolrVM;
import monty.solr.jni.PythonCall;
import monty.solr.jni.PythonMessage;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.handler.dataimport.DataImportHandler;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.search.CitationLRUCache;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This handler keeps Solr index in sync with the Invenio database.
 * Basically, on every invocation it calls Invenio to retrieve set
 * of added/updated/deleted document recids.
 * 
 * Note from the author: I don't like my code at all, it should be simpler
 * 
 * When we have these ids, we'll call the respective handlers and
 * pass them recids. This implementation extends {@link DataImportHandler}
 * therefore it is sequential. While one import is running, consecutive
 * requests to the same import handler class will respond with 
 * importStatus <b>busy</b>
 * 
 * 	@param request parameters
 * 		<p>
 * 		- <b>last_recid</b>: the recid of the reference record, it will be the
 * 			orientation point to find all newer changed/added/deleted recs
 * 			
 * 			If last_recid == -1, we start from the first document
 * 		<p>
 * 		- <b>generate</b>: boolean parameter which means empty lucene documents
 * 			should be generated in the range <b>{last_recid, max_recid}</b>
 * 
 *  		- <b>max_recid</b>: integer, marks the end of the interval, must be
 *  			supplied when using generate
 *  		If <b>generate</b> is false, then we will try to retrieve recids
 *  		from invenio and start the indexing/updates
 *  
 * 		<p>
 *  	- <b>inveniourl</b> : complete url to the Invenio search (we'll prepend query
 *        		parameters, eg. inveniourl?p=recid:x->y)
 *      <p>
 *  	- <b>updateurl</b> : complete url to the Solr update handler (this handler
 *              should fetch <b>updated</b> source documents and index them)
 *      <p>
 *  	- <b>importurl</b> : complete url to the Solr update handler (this handler
 *              should fetch <b>new</b> source documents and index them)
 *      <p>
 *  	- <b>deleteurl</b> : complete url to the Solr update handler (this handler
 *              should remove <b>deleted</b> documents from Solr index)
 *              
 *              
 *  <p>            
 *  Example configuration:
 *  <pre>
 *    last_recid: 90
 *    inveniourl: http://invenio-server/search
 *    updateurl: http://localhost:8983/solr/update-dataimport?command=full-import&dirs=/proj/fulltext/extracted    
 *    importurl: http://localhost:8983/solr/import-dataimport?command=full-import&arg1=val1&arg2=val2
 *    deleteurl: http://localhost:8983/solr/delete-dataimport?command=full-import
 *    maximport: 200
 *  
 *  using modification date of the recid 90 we discover...
 *  
 *    updated records: 53, 54, 55, 100
 *    added records: 101,103
 *    deleted records: 91,92,93,102
 *  
 *  ...which results in 3 requests (newline breaks added for readability):
 *  
 *  
 *  1. http://localhost:8983/solr/update-dataimport?command=full-import&dirs=/proj/fulltext/extracted
 *     &url=http://invenio-server/search?p=recid:53->55 OR recid:100&rg=200&of=xm
 *     
 *  2. http://localhost:8983/solr/import-dataimport?command=full-import&arg1=val1&arg2=val2
 *     &url=http://invenio-server/search?p=recid:101 OR recid:103&rg=200&of=xm
 *  
 *  3. http://localhost:8983/solr/delete-dataimport?command=full-import
 *     &url=http://invenio-server/search?p=recid:91-93 OR recid:102&rg=200&of=xm
 *  
 *  </pre>
 *  
 *  NOTE: the url parameter <b>url</b> is url-encoded (it is here in plain form for readability)
 *  
 *  <p>
 *  Also, if you want to try the update handler manually, you must encode the parameters, eg:
 *  
 *  <code>
 *  http://localhost:8983/solr/invenio_updater?last_recid=100&index=true
 *    &inveniourl=http%3A%2F%2Finvenio-server%2Fsearch
 *    &importurl=http%3A%2F%2Flocalhost%3A8983%2Fsolr%2Fwaiting-dataimport%3Fcommand%3Dfull-import%26dirs%3D%2Fproj%2Fadsx%2Ffulltext%2Fextracted
 *  </code>   
 *  
 */
public class InvenioKeepRecidUpdated extends RequestHandlerBase implements PythonCall {
	
	public static final Logger log = LoggerFactory
		.getLogger(InvenioKeepRecidUpdated.class);
	
	
	
	private volatile int counter = 0;
	private boolean asynchronous = true;
	private volatile String workerMessage = "";
	private volatile String tokenMessage = "";
	
	static String IKRU_PROPERTIES = "invenio_updater.properties"; // will be put into context
	static final String LAST_RECID = "last_recid"; // name of the param from url and also what is passed to python
	static final String LAST_UPDATE = "mod_date"; // name of the param from url and also what is passed to python
	
	static final String ADDED = "ADDED"; // datastructure returned from python with recids is keyed
	static final String UPDATED = "UPDATED";
	static final String DELETED = "DELETED";
	
	static final String PARAM_INVENIO = "inveniourl"; // url params that influence processing
	static final String PARAM_IMPORT = "importurl";
	static final String PARAM_UPDATE = "updateurl";
	static final String PARAM_DELETE = "deleteurl";
	static final String PARAM_MAXIMPORT = "maximport";
	static final String PARAM_BATCHSIZE = "batchsize";
	static final String PARAM_COMMIT = "commit";
	static final String PARAM_MAX_RECID = "max_recid";
	static final String PARAM_TOKEN = "idtoken";

	
	private String pythonFunctionName = "get_recids_changes";
	private int max_maximport = 20000;
	private int max_batchsize = 500000;
	
	
	@Override
	public void init(NamedList args) {
		
		super.init(args);
		
		if (args.get("defaults") == null) {
			return;
		}
		
		NamedList defs = (NamedList) args.get("defaults");
		
		if (defs.get("max_maximport") != null) {
			max_maximport = Integer.valueOf((String) defs.get("max_maximport"));
		}
		
		if (defs.get("max_batchsize") != null) {
			max_batchsize = Integer.valueOf((String) defs.get("max_batchsize"));
		}
		
		if (defs.get("pythonFunctionName") != null) {
			pythonFunctionName = (String) defs.get("pythonFunctionName");
		}
		
		if (defs.get("propertiesFile") != null) {
			IKRU_PROPERTIES = (String) defs.get("propertiesFile");
		}
	}
	
	
	public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) 
		throws IOException, InterruptedException
			 {

		if (isBusy()) {
			rsp.add("message",
					"Import is already running, please retry later...");
			rsp.add("importStatus", "busy");
			rsp.add("workerMessage", getWorkerMessage());
			rsp.add(PARAM_TOKEN, getToken());
			return;
		}

		setBusy(true);

		SolrParams params = req.getParams();
		Properties prop = loadProperties(params);
		setToken(prop.getProperty(PARAM_TOKEN));
		
		long start = System.currentTimeMillis();
		
		Map<String, Object> dictData = null;
		
		try {
			dictData = retrieveRecids(prop, req, rsp);
		}
		catch (RuntimeException e) {
			setBusy(false);
			throw e;
		}
		
		if (dictData == null) {
			setBusy(false);
			return;
		}

		req.getContext().put(IKRU_PROPERTIES, prop);
		
		if (isAsynchronous()) {
			runAsynchronously(dictData, req);
		}
		else {
			runSynchronously(dictData, req);
			setBusy(false);
		}
		

		long end = System.currentTimeMillis();

		rsp.add("importStatus", isBusy() ? "busy" : "idle");
		rsp.add("workerMessage", getWorkerMessage());
		rsp.add("QTime", end - start);
		setWorkerMessage("Last import finished in: " + (end - start));
		rsp.add(PARAM_TOKEN, getToken());
		setToken("");
		
	}
	
	private void setToken(String string) {
		tokenMessage = string;
	}

	private String getToken() {
		return tokenMessage;
	}

	/*
	 * The method that discovers what was changed in Invenio DB
	 */
	@SuppressWarnings("unchecked")
	protected Map<String, Object> retrieveRecids(Properties prop,
			SolrQueryRequest req,
            SolrQueryResponse rsp) {
		
		HashMap<String, Object> retData = new HashMap<String, Object>();
		
		SolrParams params = req.getParams();
		
		Integer lastRecid = null;
		String lastUpdate = null;
		if (prop.containsKey(LAST_RECID)) {
			lastRecid = Integer.valueOf(prop.getProperty(LAST_RECID));
		}
		if (prop.containsKey(LAST_UPDATE)) {
			lastUpdate = prop.getProperty(LAST_UPDATE);
		}
		
		Map<String, int[]> dictData;
		// we'll generate empty records (good just to have a mapping between invenio
		// and lucene docids; necessary for search operations)
		if (params.getBool("generate", false)) {
			Integer max_recid = params.getInt(PARAM_MAX_RECID, 0);
			if (max_recid == 0 || max_recid < lastRecid) {
				throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
						"The max_recid parameter missing!");
			}

			dictData = new HashMap<String, int[]>();
			int[] a = new int[max_recid - lastRecid];
			for (int i = 0, ii = lastRecid + 1; ii < max_recid + 1; i++, ii++) {
				a[i] = ii;
			}
			dictData.put("ADDED", a);
			retData.put(LAST_UPDATE, null);
			retData.put(LAST_RECID, max_recid);
			
		} else {
			// get recids from Invenio {'ADDED': int, 'UPDATED': int, 'DELETED':
			// int }
			
			PythonMessage message = MontySolrVM.INSTANCE
					.createMessage(pythonFunctionName)
					.setSender(this.getClass().getSimpleName())
					.setParam("max_records", params.getInt(PARAM_BATCHSIZE))
					.setParam("request", req)
					.setParam("response", rsp);
			
			if (lastRecid != null) message.setParam(LAST_RECID, lastRecid);
			if (lastUpdate != null) message.setParam(LAST_UPDATE, lastUpdate);
			
			if (lastRecid == null && lastUpdate == null) {
				message.setParam(LAST_UPDATE, getLastIndexUpdate(req));
			}
			
			log.info("Retrieving changed recs: max_records=" + params.getInt(PARAM_BATCHSIZE) + 
					" last_recid=" + lastRecid + " last_update=" + lastUpdate);
			
			MontySolrVM.INSTANCE.sendMessage(message);

			Object results = message.getResults();
			if (results == null) {
				rsp.add("message",
						"No new/updated/deleted records inside Invenio.");
				rsp.add("importStatus", "idle");
				return null;
			}
			dictData = (HashMap<String, int[]>) results;
			retData.put(LAST_UPDATE, (String) message.getParam(LAST_UPDATE));
			retData.put(LAST_RECID, (Integer) message.getParam(LAST_RECID));
			
			log.info("Retrieved: last_update=" + retData.get(LAST_UPDATE) + 
					" last_recid=" + retData.get(LAST_RECID));
		}
		retData.put("dictData", dictData);
		return retData;
    }

	
	private String getLastIndexUpdate(SolrQueryRequest req) {
	  SolrIndexSearcher searcher = req.getSearcher();
	  // Invenio uses mod_date.strftime(format="%Y-%m-%d %H:%M:%S") -> '2013-11-29 16:40:33'
	  SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S");
	  Date date = new Date(searcher.getOpenTime());
	  return df.format(date);
  }


	public void setPythonFunctionName(String name) {
		pythonFunctionName = name;
	}
	
	public String getPythonFunctionName() {
		return pythonFunctionName;
	}

	
	private void runAsynchronously(Map<String, Object> dictData, 
			SolrQueryRequest req) {
		
		final Map<String, Object> dataToProcess = dictData;
		final SolrQueryRequest localReq = new LocalSolrQueryRequest(req.getCore(), req.getParams());
		localReq.getContext().put(IKRU_PROPERTIES, req.getContext().get(IKRU_PROPERTIES));
		
		new Thread(new Runnable() {

			public void run() {
				try {
					runSynchronously(dataToProcess, localReq);
				} catch (IOException e) {
					log.error(e.getLocalizedMessage());
					log.error(e.getStackTrace().toString());
				} catch (InterruptedException e) {
					log.error(e.getLocalizedMessage());
					log.error(e.getStackTrace().toString());
				} finally {
					setBusy(false);
					localReq.close();
				}
			}
		}).start();
	}


	public void setAsynchronous(boolean val) {
		asynchronous = val;
	}
	
	
	public boolean isAsynchronous() {
		return asynchronous;
	}
	
	private void setBusy(boolean b) {
		if (b == true) {
			counter++;
		}
		else {
			counter--;
		}
	}

	public boolean isBusy() {
		if (counter<0) {
			throw new IllegalStateException("Huh, 2+2 is not 4?! Should never happen.");
		}
		return counter > 0;
	}
	
	public void setWorkerMessage(String msg) {
		log.info(msg);
		workerMessage = msg;
	}
	
	public String getWorkerMessage() {
		return workerMessage;
	}
	
	
	/*
	 * The main method/logic
	 */
	private void runSynchronously(Map<String, Object> data, 
			SolrQueryRequest req) 
			throws MalformedURLException, IOException, InterruptedException {
		
		
		log.info("=============================================================================");
		log.info(data.toString());
		log.info(req.toString());
		log.info(req.getParamString());
		log.info("=============================================================================");
		
		SolrParams params = req.getParams();
		SolrCore core = req.getCore();
		
		String importurl = params.get(PARAM_IMPORT, null);
		String updateurl = params.get(PARAM_UPDATE, null);
		String deleteurl = params.get(PARAM_DELETE, null);
		
		
		@SuppressWarnings("unchecked")
		HashMap<String, int[]> dictData = (HashMap<String, int[]>) data.get("dictData");
		Properties prop = (Properties) req.getContext().get(IKRU_PROPERTIES);
		
		if (dictData.containsKey(ADDED) && dictData.get(ADDED).length > 0) {
			setWorkerMessage("Phase 1/3. Adding records: " + dictData.get(ADDED).length);
			if (importurl != null) {
				if (importurl.equals("blankrecords")) {
					runProcessingAdded(dictData.get(ADDED), req);
				}
				else {
					runProcessing(core, importurl, dictData.get(ADDED), req);
				}
			}
		}
		
		if (dictData.containsKey(UPDATED) && dictData.get(UPDATED).length > 0) {
			setWorkerMessage("Phase 2/3. Updating records: " + dictData.get(UPDATED).length);
			if (updateurl != null) {
				if (updateurl.equals("blankrecords")) {
					runProcessingUpdated(dictData.get(UPDATED), req);
				}
				else {
					runProcessing(core, updateurl, dictData.get(UPDATED), req);
				}
			}
		}

		if (dictData.containsKey(DELETED) && dictData.get(DELETED).length > 0 ) {
			setWorkerMessage("Phase 3/3. deleting records: " + dictData.get(DELETED).length);
			if (deleteurl != null) {
				if (deleteurl.equals("blankrecords")) {
					runProcessingDeleted(dictData.get(DELETED), req);
				}
				else {
					runProcessing(core, deleteurl, dictData.get(DELETED), req);
				}
			}
		}

		// save the state into the properties (the modification date must be there
		// in all situations 
		prop.put(LAST_UPDATE, (String) data.get(LAST_UPDATE));
		prop.put(LAST_RECID, String.valueOf((Integer) data.get(LAST_RECID)));
		prop.remove(PARAM_BATCHSIZE);
		prop.remove(PARAM_MAXIMPORT);
		prop.remove(PARAM_TOKEN);
		saveProperties(prop);
		
		
		if (params.getBool(PARAM_COMMIT, false)) {
			setWorkerMessage("Phase 3/3. Writing index...");
			CommitUpdateCommand updateCmd = new CommitUpdateCommand(req, false);
			req.getCore().getUpdateHandler().commit(updateCmd);
		}
	    
    }


		
	private void runProcessing(SolrCore core, String handlerUrl, int[] recids,
			SolrQueryRequest req) throws MalformedURLException, IOException, InterruptedException {
		
		
		
		URI u = null;
		SolrRequestHandler handler = null;
		try {
			u = new URI(handlerUrl);
			String p = u.getPath();
			if (u.getHost() == null || u.getHost() == "") {
				if (core.getRequestHandler(p) != null) {
					handler = core.getRequestHandler(p);
				}
				else if (!p.startsWith("/") && core.getRequestHandler("/" + p) != null) {
					handler = core.getRequestHandler("/" + p);
				}
				
				if (handler != null) {
					Map<String, List<String>> handlerParams = WebUtils.parseQuery(u.getQuery());
					HashMap<String, String[]> hParams = new HashMap<String, String[]>();
					for (String val: handlerParams.keySet()) {
						String[] nV = new String[handlerParams.get(val).size()];
						int i = 0;
						for (String v: handlerParams.get(val)) {
							nV[i] = v;
							i++;
						}
						hParams.put(val, nV);
					}
					
					runProcessingInternally(handler, recids, req, hParams);
					return;
				}
			}
		}
		catch (URISyntaxException e) {
			e.printStackTrace();
		}
		
		runProcessingUpload(handlerUrl, recids, req);
	}

	public File getPropertyFile() {
		return new File(IKRU_PROPERTIES);
	}
	
	private Properties loadProperties(SolrParams params) throws FileNotFoundException, IOException {
	    	Properties prop = new Properties();
	    	
			
	    	File f = getPropertyFile();
	    	if (f.exists()) {
	    		FileInputStream input = new FileInputStream(f);
	    		prop.load(input);
	    		input.close();
	    	}
	    	
	    	String prop_recid = null;
	    	if (prop.containsKey(LAST_RECID)) {
	    		prop_recid = (String) prop.remove(LAST_RECID);
	    	}
	    	
	    	String prop_mod_date = null;
	    	if (prop.containsKey(LAST_UPDATE)) {
	    		prop_mod_date = (String) prop.remove(LAST_UPDATE);
	    	}
	    	
	    	boolean userParam = false;
	    	
	    	// parameters in url have always precedence (if both set
	    	// it is up to the python to figure out who has precedence
			if (params.getInt(LAST_RECID) != null) {
				prop.put(LAST_RECID, params.get(LAST_RECID));
				userParam = true;
			} 
			
			if (params.get(LAST_UPDATE, null) != null) {
				prop.put(LAST_UPDATE, params.get(LAST_UPDATE));
				userParam = true;
			}
			
			if (!userParam) {
				// when no user params were supplied, prefer the mod_date over recid
				if (prop_mod_date != null) {
					prop.put(LAST_UPDATE, prop_mod_date);
				}
				else if (prop_recid != null) {
					prop.put(LAST_RECID, prop_recid);
				}
			}
			
			if (params.get(PARAM_BATCHSIZE, null) != null) {
				int bs = params.getInt(PARAM_BATCHSIZE);
				if (bs > max_batchsize) {
					prop.put(PARAM_BATCHSIZE, max_batchsize);
				}
				else {
					prop.put(PARAM_BATCHSIZE, bs);
				}
			}
			
			if (params.get(PARAM_MAXIMPORT, null) != null) {
				int mi = params.getInt(PARAM_MAXIMPORT);
				if (mi > max_maximport) {
					prop.put(PARAM_MAXIMPORT, max_maximport);
				}
				else {
					prop.put(PARAM_MAXIMPORT, mi);
				}
			}
			
			prop.put(PARAM_TOKEN, params.get(PARAM_TOKEN, ""));
			
	    	return prop;
	}
	
	private void saveProperties(Properties prop) throws IOException {
		File f = getPropertyFile();
		FileOutputStream out = new FileOutputStream(f);
		prop.store(out, null);
		out.close();
	}
	
	

	
	/*
	 * When method-blankrecords we are creating/adding empty docs
	 */
	
	protected void runProcessingAdded(int[] recids, SolrQueryRequest req) throws IOException {
		
		IndexSchema schema = req.getSchema();
		UpdateHandler updateHandler = req.getCore().getUpdateHandler();
		String uniqField = schema.getUniqueKeyField().getName();
		
		AddUpdateCommand addCmd = new AddUpdateCommand(req);
		//addCmd.commitWithin = params.getInt(UpdateParams.COMMIT_WITHIN, -1);
		//addCmd.setFlags(UpdateCommand.BUFFERING);
		
		if (recids.length > 0) {
			for (int i = 0; i < recids.length; i++) {
			  addCmd.clear();
			  addCmd.solrDoc = new SolrInputDocument();
			  addCmd.solrDoc.addField(uniqField,	recids[i]);
				updateHandler.addDoc(addCmd);
			}
		}
			
	}
	
	protected void runProcessingUpdated(int[] recids, SolrQueryRequest req) throws IOException {
		IndexSchema schema = req.getSchema();
		UpdateHandler updateHandler = req.getCore().getUpdateHandler();
		String uniqField = schema.getUniqueKeyField().getName();
		
		AddUpdateCommand addCmd = new AddUpdateCommand(req);
		//addCmd.commitWithin = params.getInt(UpdateParams.COMMIT_WITHIN, -1);
		//addCmd.setFlags(UpdateCommand.BUFFERING);
		

      if (recids.length > 0) {
			
//			Map<Integer, Integer> map = DictionaryRecIdCache.INSTANCE
//					.getTranslationCache(req.getSearcher().getAtomicReader(), 
//							uniqField);
			
			for (int i = 0; i < recids.length; i++) {
//				if (!map.containsKey(recids[i])) {
					addCmd.clear();
					addCmd.solrDoc = new SolrInputDocument();
					addCmd.solrDoc.addField(uniqField,	recids[i]);
					updateHandler.addDoc(addCmd);
//				}
//				else {
//				  log.error("Trying to update a record which doesn't have recid! recid=" + recids[i]);
//				}
			}
		}
	}
	
	protected void runProcessingDeleted(int[] recids, SolrQueryRequest req) throws IOException {
		UpdateHandler updateHandler = req.getCore().getUpdateHandler();
		
		DeleteUpdateCommand delCmd = new DeleteUpdateCommand(req);
		//delCmd.commitWithin = params.getInt(UpdateParams.COMMIT_WITHIN, -1);
		//delCmd.setFlags(UpdateCommand.BUFFERING);
		

    if (recids.length > 0) {
			for (int i = 0; i < recids.length; i++) {
			  delCmd.clear();
				delCmd.id = Integer.toString(recids[i]);
				updateHandler.delete(delCmd);
			}
		}
	}
	
	
	/*
	 * Internally calling dataimport handler
	 */
	protected void runProcessingInternally(SolrRequestHandler handler, 
			int[] recids, SolrQueryRequest req, HashMap<String, String[]> hParams) 
		throws IOException, InterruptedException {
		
		SolrParams params = req.getParams();
		Integer maximport = params.getInt(PARAM_MAXIMPORT, max_maximport);
		String inveniourl = params.get(PARAM_INVENIO, null);
		List<String> queryParts = getQueryIds(maximport, recids);
		
		LocalSolrQueryRequest localReq = null;
		int i = 0;
		for (String queryPart : queryParts) {
			i++;
			String[] invP = new String[1];
			invP[0] = getInternalURL(inveniourl, queryPart, maximport);
			hParams.put("url", invP);
			localReq = new LocalSolrQueryRequest(req.getCore(), hParams);
			SolrQueryResponse rsp = new SolrQueryResponse();
			try {
				req.getCore().execute(handler, localReq, rsp);
			}
			finally {
				localReq.close();
			}
			
			if (queryParts.size() > 1) {
				log.warn("Warning, we have started the importer, but it runs in parallel!");
				log.warn("And we will initiate another: " + (queryParts.size() - i));
			}
		}
	}
	
	protected void runProcessingUpload(String handlerUrl, int[] recids, SolrQueryRequest req) 
		throws MalformedURLException, IOException, InterruptedException {
		SolrParams params = req.getParams();
		Integer maximport = params.getInt(PARAM_MAXIMPORT, max_maximport );
		String inveniourl = params.get(PARAM_INVENIO, null);
		
		List<String> urlsToFetch = new ArrayList<String>();
		List<String> queryParts = getQueryIds(maximport, recids);
		for (String queryPart : queryParts) {
			urlsToFetch.add(getFetchURL(handlerUrl, inveniourl,
					queryPart, maximport));
		}
		runUpload(urlsToFetch);
	}
	
	
	/*
	 * Calling external URL's
	 */
	protected void runUpload(List<String> urlsToFetch) throws MalformedURLException, IOException,
			InterruptedException {
	  int i = 0;
		while (urlsToFetch.size() > 0) {
			String url = urlsToFetch.remove(0);
			String html = IOUtils.toString(new URL(url).openStream());
			while (html.contains("busy")) {
				Thread.sleep(200);
			}
			i++;
			if (i> 20) {
			  throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE, "The remote url is constantly busy: " + url);
			}
		}

	}

	protected String getFetchURL(String importurl, String inveniourl,
			String queryPart, Integer maximport)
			throws UnsupportedEncodingException {
		String sign = importurl.contains("?") ? "&" : "?";
		String sign2 = inveniourl.contains("?") ? "&" : "?";
		return importurl
				+ sign
				+ "url="
				+ java.net.URLEncoder.encode(
					inveniourl + sign2 + "p=" + java.net.URLEncoder.encode(queryPart, "UTF-8") + "&rg=" + maximport + "&of=xm",
					"UTF-8");
	}
	
	
	
	public static String getInternalURL(String sourceUrl,
			String queryPart, Integer maximport)
			throws UnsupportedEncodingException {
		String sign = sourceUrl.contains("?") ? "&" : "?";
		return sourceUrl
				+ sign
				+ "p=" + java.net.URLEncoder.encode(queryPart, "UTF-8") 
				+ "&rg=" + maximport + "&of=xm";
	}

	// ////////////////////// SolrInfoMBeans methods //////////////////////

	/**
	 * Will split array of intgs into query "recid:4->15 OR recid:78 OR recid:80->82"
	 */
	public static List<String> getQueryIds(int maxspan, int[] recids) {

		Arrays.sort(recids);
		List<String> ret = new ArrayList<String>();
		StringBuilder query;

		int i = 0;

		while (i < recids.length) {
			int delta = 1;
			int last_id = 0;
			query = new StringBuilder();
			query.append("recid:" + recids[i]);
			for (i++; i < recids.length; i++) {
				if (delta >= maxspan) {
					break;
				}
				if (recids[i] - 1 == recids[i - 1]) {
					last_id = recids[i];
					delta += 1;
					continue;
				}
				if (last_id > 0) {
					query.append("->" + last_id);
					last_id = 0;
				}
				query.append(" OR recid:" + recids[i]);
				delta += 1;
			}

			if (last_id > 0) {
				query.append("->" + last_id);
			}

			ret.add(query.toString());
		}
		return ret;

	}
	
	
	public String getVersion() {
		return "";
	}

	
	public String getDescription() {
		return "Updates the Invenio recid with the missing/new docs (if any)";
	}

	
	public String getSourceId() {
		return "";
	}

	
	public String getSource() {
		return "";
	}
	
	
}
