package com.couchbase.cblite.phonegap;

import android.content.Context;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CordovaInterface;
import org.json.JSONArray;

import com.couchbase.lite.android.AndroidContext;
import com.couchbase.lite.Manager;
import com.couchbase.lite.listener.LiteListener;
import com.couchbase.lite.listener.LiteServlet;
import com.couchbase.lite.listener.Credentials;
import com.couchbase.lite.router.URLStreamHandlerFactory;
import com.couchbase.lite.View;
import com.couchbase.lite.javascript.JavaScriptViewCompiler;
import com.couchbase.lite.util.Log;

import java.io.IOException;
import java.io.File;

import com.couchbase.lite.auth.BasicAuthenticator;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.DocumentChange;
import com.couchbase.lite.Database.ChangeListener;
import com.couchbase.lite.Database.ChangeEvent;
import com.couchbase.lite.replicator.Replication;
import java.net.MalformedURLException;
import java.net.URL;

import org.jdeferred.*;
import org.jdeferred.impl.*;

import org.xwalk.core.XWalkView;

public class CBLite extends CordovaPlugin {

  private static final String TAG = CBLite.class.getSimpleName();
	private static final int DEFAULT_LISTEN_PORT = 5984;
	private boolean initFailed = false;
	private int listenPort;
  private Credentials allowedCredentials;
  private Manager server;

  private XWalkView view;

	/**
	 * Constructor.
	 */
	public CBLite() {
		super();
		System.out.println("CBLite() constructor called");
	}

	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		System.out.println("initialize() called");

		super.initialize(cordova, webView);
		initCBLite();

	}

	private void initCBLite() {
		try {

		  allowedCredentials = new Credentials("hi", "there");

			URLStreamHandlerFactory.registerSelfIgnoreError();

			View.setCompiler(new JavaScriptViewCompiler());

			server = startCBLite(this.cordova.getActivity());

			listenPort = startCBLListener(DEFAULT_LISTEN_PORT, server, allowedCredentials);

			System.out.println("initCBLite() completed successfully");




		} catch (final Exception e) {
			e.printStackTrace();
			initFailed = true;
		}
	}

  private Promise replicate(String local, String remote, boolean isPush) {
      final Deferred deferred = new DeferredObject();

      try {
        Database localDB = server.getExistingDatabase(local);
        URL remoteURL = new URL(remote);
        BasicAuthenticator auth = new BasicAuthenticator(allowedCredentials.getLogin(), allowedCredentials.getPassword());

        if(isPush) {
          final Replication push = localDB.createPushReplication(remoteURL);
          push.setAuthenticator(auth);
          push.addChangeListener(new Replication.ChangeListener() {
              @Override
              public void changed(Replication.ChangeEvent event) {
                  boolean active = push.getStatus() == Replication.ReplicationStatus.REPLICATION_ACTIVE;
                  if(active) {
                      return;
                  } else {
                      if(push.getLastError() != null) {
                          deferred.reject(push.getLastError().getMessage());
                      } else {
                          deferred.resolve("OK");
                      }
                  }
              }
          });
          push.start();
        } else {
          final Replication pull = localDB.createPullReplication(remoteURL);
          pull.setAuthenticator(auth);
          pull.addChangeListener(new Replication.ChangeListener() {
              @Override
              public void changed(Replication.ChangeEvent event) {
                  boolean active = pull.getStatus() == Replication.ReplicationStatus.REPLICATION_ACTIVE;
                  if(active) {
                      return;
                  } else {
                      if(pull.getLastError() != null) {
                          deferred.reject(pull.getLastError().getMessage());
                      } else {
                          deferred.resolve("done");
                      }
                  }
              }
          });
          pull.start();
        }
      } catch(CouchbaseLiteException e) {
        deferred.reject("CouchbaseLiteException");
      } catch(MalformedURLException e) {
        deferred.reject("URL Exception");
      }

      return deferred.promise();
  }

  private void setupTrigger(final String dbName, CallbackContext callbackContext) {
      try {
        Database localDB = server.getExistingDatabase(dbName);
        localDB.addChangeListener(new ChangeListener() {
            public void changed(ChangeEvent event) {
                JSONArray changes = new JSONArray();
                for (DocumentChange change : event.getChanges()) {
                    changes.put(change.getDocumentId());
                }

                final String js = "(function() {" +
                            "var e = new Event('couchbase:" + dbName + "');" +
                            "e.value = '" + changes.toString() + "';" +
                            "document.dispatchEvent(e);" +
                            "})()";
                cordova.getActivity().runOnUiThread(new Runnable() {
                    public void run() {
                        CBLite.this.view.evaluateJavascript(js, null);
                    }
                });
            }
        });
        callbackContext.success("OK");
      } catch(CouchbaseLiteException e) {
        callbackContext.error("couldn't add event");
      }


  }



	@Override
	public boolean execute(String action, JSONArray args,
			final CallbackContext callback) {
    this.view = (XWalkView) this.webView.getView();

		try {
		  if (action.equals("getURL")) {
				if (initFailed == true) {
					callback.error("Failed to initialize couchbase lite.  See console logs");
					return false;
				} else {
					String callbackRespone = String.format(
							"http://%s:%s@localhost:%d/",
                            allowedCredentials.getLogin(),
                            allowedCredentials.getPassword(),
                            listenPort
                    );

					callback.success(callbackRespone);

					return true;
				}
      } else if(action.equals("replicate")) {
        final String local = args.getString(0);
        final String remote = args.getString(1);
        Promise bothWays = replicate(local, remote, false)
            .then(new DonePipe <String, String, Exception, Void>() {
              public Promise<String, Exception, Void> pipeDone(String result) {
                 return CBLite.this.replicate(local, remote, true);
              }
            })
            .done(new DoneCallback() {
              public void onDone(Object result) {
                  callback.success("OK");
              }
            })
            .fail(new FailCallback() {
              public void onFail(Object result) {
                  callback.error("Error: " + result);
              }
            });
        return true;
      } else if(action.equals("listen")) {
        String dbName = args.getString(0);
        setupTrigger(dbName, callback);
      }
    } catch (final Exception e) {
      e.printStackTrace();
      callback.error(e.getMessage());
    }

		return false;
	}

	protected Manager startCBLite(Context context) {
		Manager manager;
		try {
		  Manager.enableLogging(Log.TAG, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_SYNC, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_QUERY, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_VIEW, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_CHANGE_TRACKER, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_BLOB_STORE, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_DATABASE, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_LISTENER, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_MULTI_STREAM_WRITER, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_REMOTE_REQUEST, Log.VERBOSE);
			Manager.enableLogging(Log.TAG_ROUTER, Log.VERBOSE);
			manager = new Manager(new AndroidContext(context), Manager.DEFAULT_OPTIONS);

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return manager;
	}

	private int startCBLListener(int listenPort, Manager manager, Credentials allowedCredentials) {

		LiteListener listener = new LiteListener(manager, listenPort, allowedCredentials);
		int boundPort = listener.getListenPort();
		Thread thread = new Thread(listener);
		thread.start();
		return boundPort;

	}

	public void onResume(boolean multitasking) {
		System.out.println("CBLite.onResume() called");
	}

	public void onPause(boolean multitasking) {
		System.out.println("CBLite.onPause() called");
	}


}

