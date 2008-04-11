/*
   Copyright 2007 Fourspaces Consulting, LLC

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.fourspaces.couchdb;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This represents a particular database on the CouchDB server
 * <p>
 * Using this object, you can get/create/update/delete documents.
 * You can also call views (named and adhoc) to query the underlying database.
 * 
 * @author mbreese
 *
 */
public class Database {
	Log log = LogFactory.getLog(Database.class);
	private final String name;
	private int documentCount;
	private int updateSeq;
	
	private Session session;
	
	/**
	 * C-tor only used by the Session object.  You'd never call this directly.
	 * @param json
	 * @param session
	 */
	Database(JSONObject json, Session session) {
		name = json.getString("db_name");
		documentCount = json.getInt("doc_count");
		updateSeq = json.getInt("update_seq");
		
		this.session = session;
	}
	
	/**
	 * The name of the database
	 * @return
	 */
	public String getName() {
		return name;
	}
	/**
	 * The number of documents in the database <b>at the time that it was retrieved from the session</b>
	 * This number probably isn't accurate after the initial load... so if you want an accurate
	 * assessment, call Session.getDatabase() again to reload a new database object.
	 * @return
	 */
	public int getDocumentCount() {
		return documentCount;
	}
	/**
	 * The update seq from the initial database load.  The update sequence is the 'revision id' of an entire database. Useful for getting all documents in a database since a certain revision
	 * @see getAllDocuments()
	 * @return
	 */
	public int getUpdateSeq() {
		return updateSeq;
	}

	/**
	 * Runs the standard "_all_docs" view on this database
	 * @return ViewResults - the results of the view... this can be iterated over to get each document.
	 */
	public ViewResults getAllDocuments() {
		return view("_all_docs");
	}
	
	/**
	 * Runs "_all_docs_by_update_seq?startkey=revision" view on this database
	 * @return ViewResults - the results of the view... this can be iterated over to get each document.
	 */	
	public ViewResults getAllDocuments(int revision) {
		return view("_all_docs_by_seq?startkey=" + revision);
	}

	/**
	 * Runs a named view on the database
	 * This will run a view and apply any filtering that is requested (reverse, startkey, etc).
	 * <i>Not currently working in CouchDB code</i>
	 * 
	 * @param view
	 * @return
	 */
	public ViewResults view(View view) {
		CouchResponse resp = session.get(name+"/"+view.getFullName(), view.getQueryString());		
		if (resp.isOk()) {
			ViewResults results = new ViewResults(view,resp.getBodyAsJSON());
			results.setDatabase(this);
			return results;
		}
		return null;
	}
	
	/**
	 * Runs a named view
	 * <i>Not currently working in CouchDB code</i>
	 * @param name - the fullname (including the document name) ex: foodoc:viewname
	 * @return
	 */

	public ViewResults view(String fullname) {
		return view(new View(fullname));
	}

	/**
	 * Runs an ad-hoc view from a string 
	 * @param function - the Javascript function to use as the filter.
	 * @return results
	 */
	public ViewResults adhoc(String function) {
		return adhoc(new AdHocView(function));
	}
	
	/**
	 * Runs an ad-hoc view from an AdHocView object.  You probably won't use this much, unless
	 * you want to add filtering to the view (reverse, startkey, etc...)
	 * 
	 * @param view
	 * @return
	 */
	public ViewResults adhoc(AdHocView view) {
		CouchResponse resp = session.post(name+"/_temp_view", view.getFunction());
		if (resp.isOk()) {
			ViewResults results = new ViewResults(view,resp.getBodyAsJSON());
			results.setDatabase(this);
			return results;
		} else {
			log.warn("Error executing view - "+resp.getErrorId()+" "+resp.getErrorReason());
		}
		return null;
	}

	/**
	 * Save a document at the given _id
	 * <p>
	 * if the docId is null or empty, then this performs a POST to the database and retrieves a new
	 * _id.
	 * <p>
	 * Otherwise, a PUT is called.
	 * <p>
	 * Either way, a new _id and _rev are retrieved and updated in the Document object
	 * 
	 * @param doc
	 * @param docId
	 */
	public void saveDocument(Document doc, String docId) {
		CouchResponse resp;
		if (docId==null || docId.equals("")) {
			resp= session.post(name+"/",doc.getJSONObject().toString());
		} else {
			resp= session.put(name+"/"+docId,doc.getJSONObject().toString());
		}
		
		if (resp.isOk()) {
			try {
				if (doc.getId()==null || doc.getId().equals("")) {
					doc.setId(resp.getBodyAsJSON().getString("id"));
				}
				doc.setRev(resp.getBodyAsJSON().getString("rev"));
			} catch (JSONException e) {
				e.printStackTrace();
			}
			doc.setDatabase(this);
		} else {
			log.warn("Error adding document - "+resp.getErrorId()+" "+resp.getErrorReason());
		}
	}
	
	/**
	 * Save a document w/o specifying an id (can be null)
	 * @param doc
	 */
	public void saveDocument(Document doc) {
		saveDocument(doc,doc.getId());
	}
	
	/**
	 * Retrieves a document from the CouchDB database
	 * @param id
	 * @return
	 */
	public Document getDocument(String id) {
		return getDocument(id,null,false);
	}
	/**
	 * Retrieves a document from the database and asks for a list of it's revisions.
	 * The list of revision keys can be retrieved from Document.getRevisions();
	 * 
	 * @param id
	 * @return
	 */
	public Document getDocumentWithRevisions(String id) {
		return getDocument(id,null,true);
	}

	/**
	 * Retrieves a specific document revision
	 * @param id
	 * @param revision
	 * @return
	 */
	public Document getDocument(String id, String revision) {
		return getDocument(id,revision,false);
	}
	
	/**
	 * Retrieves a specific document revision and (optionally) asks for a list of all revisions 
	 * @param id
	 * @param revision
	 * @param showRevisions
	 * @return the document
	 */
	public Document getDocument(String id, String revision, boolean showRevisions) {
		CouchResponse resp;
		Document doc = null;
		if (revision!=null && showRevisions) {
			resp=session.get(name+"/"+id,"rev="+revision+"&full=true");
		} else if (revision!=null && !showRevisions) {
			resp=session.get(name+"/"+id,"rev="+revision);
		} else if (revision==null && showRevisions) {
			resp=session.get(name+"/"+id,"revs=true");
		} else {
			resp=session.get(name+"/"+id);
		}
		if (resp.isOk()) {
			doc = new Document(resp.getBodyAsJSON());
			doc.setDatabase(this);
		} else {
			log.warn("Error getting document - "+resp.getErrorId()+" "+resp.getErrorReason());
		}
		return doc;
	}
	
	/**
	 * Deletes a document
	 * @param d
	 * @return was the delete successful?
	 */
	public boolean deleteDocument(Document d) {
		CouchResponse resp = session.delete(name+"/"+d.getId() + "?rev=" + d.getRev());
		
		if(resp.isOk()) {
			return true;
		} else {
			log.warn("Error deleting document - "+resp.getErrorId()+" "+resp.getErrorReason());
			return false;
		}
		
	}
}
