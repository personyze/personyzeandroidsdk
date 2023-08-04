package com.personyze.androidsdk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

interface PersonyzeMessageHandler {}

public class PersonyzeAction implements Serializable
{	final protected int id;
	private HashMap<String, String> data;
	private int cacheVersion;
	protected String name;
	String contentType;
	String contentParam;
	String contentBegin;
	String contentEnd;
	String libsApp;
	ArrayList<PersonyzePlaceholder> placeholders;

	public class Clicked
	{	public int actionId;
		public String href;
		public String status;
		public String arg;
	}

	PersonyzeAction(int id)
	{	this.id = id;
	}

	PersonyzeAction(int id, HashMap<String, String> data, int cacheVersion)
	{	this.id = id;
		this.data = data;
		this.cacheVersion = cacheVersion;
	}

	public boolean equals(Object other)
	{	return (other instanceof PersonyzeAction) && id==((PersonyzeAction)other).id;
	}

	boolean fromStorage(SharedPreferences storage)
	{	Set<String> placeholdersSet = storage.getStringSet("Action Placeholders "+id, null);
		if (placeholdersSet == null)
		{	placeholders = null;
			return false;
		}
		else
		{	placeholders = new ArrayList<>(placeholdersSet.size());
			for (String v : placeholdersSet)
			{	PersonyzePlaceholder placeholder = new PersonyzePlaceholder(Integer.parseInt(v));
				if (!placeholder.fromStorage(storage))
				{	return false;
				}
				placeholders.add(placeholder);
			}
		}
		name = storage.getString("Action Name "+id, null);
		contentType = storage.getString("Action Content Type "+id, null);
		contentParam = storage.getString("Action Content Param "+id, null);
		contentBegin = storage.getString("Action Content Begin "+id, null);
		contentEnd = storage.getString("Action Content End "+id, null);
		libsApp = storage.getString("Action Libs "+id, null);
		cacheVersion = storage.getInt("Action Cache Version "+id, 0);
		return name != null;
	}

	void toStorage(SharedPreferences storage)
	{	SharedPreferences.Editor editor = storage.edit();
		editor.putString("Action Name "+id, name);
		editor.putString("Action Content Type "+id, contentType);
		editor.putString("Action Content Param "+id, contentParam);
		editor.putString("Action Content Begin "+id, contentBegin);
		editor.putString("Action Content End "+id, contentEnd);
		editor.putString("Action Libs "+id, libsApp);
		editor.putInt("Action Cache Version "+id, cacheVersion);
		Set<String> placeholdersSet = new HashSet<>();
		for (PersonyzePlaceholder placeholder : placeholders)
		{	placeholdersSet.add(Integer.toString(placeholder.id));
		}
		editor.putStringSet("Action Placeholders "+id, placeholdersSet);
		editor.apply();
	}

	void dataToStorage(Context context) throws IOException
	{	File file = new File(context.getCacheDir(), "Personyze Action Data "+id);
		if (data != null)
		{	new ObjectOutputStream(new FileOutputStream(file)).writeObject(data);
		}
		else if (file.exists() && !file.delete())
		{	throw new IOException("Couldn't delete file");
		}
	}

	void dataFromStorage(Context context) throws IOException, ClassNotFoundException
	{	File file = new File(context.getCacheDir(), "Personyze Action Data "+id);
		data = null;
		if (file.exists())
		{	data = (HashMap<String, String>)new ObjectInputStream(new FileInputStream(file)).readObject();
		}
	}

	public int getId()
	{	return id;
	}

	public String getName()
	{	return name==null ? "" : name;
	}

	public String getContentType()
	{	return contentType==null ? "" : contentType;
	}

	public String getContent()
	{	return String.format
		(	"%s%s%s",
			contentBegin==null ? "" : contentBegin,
			data==null || contentParam==null || contentParam.length()==0 || !data.containsKey(contentParam) ? "" : data.get(contentParam),
			contentEnd==null ? "" : contentEnd
		);
	}

	public ArrayList<HashMap<String, String>> getContentJsonArray()
	{	if (contentType!=null && contentType.equals("application/json"))
		{	try
			{	Object maybeArray = new JSONTokener(getContent()).nextValue();
				if (maybeArray instanceof JSONArray)
				{	JSONArray array = (JSONArray)maybeArray;
					int nItems = array.length();
					ArrayList<HashMap<String, String>> result = new ArrayList<>(nItems);
					for (int i=0; i<nItems; i++)
					{	Object maybeObject = array.get(i);
						if (maybeObject instanceof JSONObject)
						{	JSONObject object = (JSONObject)maybeObject;
							HashMap<String, String> item = new HashMap<>(object.length());
							Iterator<String> keys = object.keys();
							while (keys.hasNext())
							{	String key = keys.next();
								item.put(key, ""+object.get(key));
							}
							result.add(item);
						}
					}
					return result;
				}
			}
			catch (Exception error)
			{	// so return null
			}
		}
		return null;
	}

	public String getContentHtmlDoc()
	{	if (contentType!=null && contentType.equals("text/html"))
		{	StringBuilder html = new StringBuilder("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head><body style=\"visibility:hidden\" onload=\"document.body.style.visibility=''\"><script src=\"");
			html.append(PersonyzeTracker.WEB_VIEW_LIB_URL);
			html.append("?v=");
			html.append(cacheVersion);
			html.append("\"></script>");
			if (libsApp != null)
			{   for (String lib : libsApp.split(","))
				{   lib = lib.trim();
					if (!lib.isEmpty())
					{   html.append("<script src=\"");
						html.append(PersonyzeTracker.LIBS_URL);
						html.append(lib);
						html.append(".js?v=");
						html.append(cacheVersion);
						html.append("\"></script>");
					}
				}
			}
			if (contentBegin != null)
			{	html.append(contentBegin);
			}
			if (data!=null && contentParam!=null && contentParam.length()!=0 && data.containsKey(contentParam))
			{	html.append(data.get(contentParam));
			}
			if (contentEnd != null)
			{	html.append(contentEnd);
			}
			html.append("<script>_S_T.new_elem(document.body, null)</script></body></html>");
			return html.toString();
		}
		return null;
	}

	public void renderOnWebView(WebView webView)
	{	renderOnWebView(webView, null);
	}

	public void renderOnWebView(WebView webView, final PersonyzeTracker.Async<Clicked> asyncClicked)
	{	final String html = getContentHtmlDoc();
		if (html != null)
		{	final int actionId = id;
			webView.getSettings().setJavaScriptEnabled(true);
			if (asyncClicked != null)
			{	webView.removeJavascriptInterface("personyze_message_handler");
				webView.addJavascriptInterface
				(	new PersonyzeMessageHandler()
					{	@JavascriptInterface public void postMessage(String data)
						{	try
							{	JSONObject object = (JSONObject)new JSONTokener(data).nextValue();
								final Clicked clicked = new Clicked();
								clicked.actionId = actionId;
								clicked.href = object.getString("href");
								clicked.status = object.getString("clicked");
								clicked.arg = object.getString("arg");
								new Handler(Looper.getMainLooper()).post
								(	new Runnable()
									{	@Override public void run()
										{	asyncClicked.callback(clicked);
										}
									}
								);
							}
							catch (Exception e)
							{	// ignore
							}
						}
					},
					"personyze_message_handler"
				);
				webView.loadData("", "text/html", null); // otherwise addJavascriptInterface() will not be applied
			}
			webView.loadDataWithBaseURL(PersonyzeTracker.WEBVIEW_BASE_URL, html, "text/html; charset=utf-8", "utf-8", null);
			reportExecuted();
		}
	}

	public void reportExecuted()
	{	PersonyzeTracker.inst.reportActionStatus(id, "executed", "");
	}

	public void reportClick()
	{	PersonyzeTracker.inst.reportActionStatus(id, "target", "");
	}

	public void reportClose()
	{	PersonyzeTracker.inst.reportActionStatus(id, "close", "0");
	}

	public void reportClose(int dontShowSessions)
	{	PersonyzeTracker.inst.reportActionStatus(id, "close", ""+(dontShowSessions<0 ? 0 : dontShowSessions));
	}

	public void reportProductClick(String productId)
	{	if (!productId.isEmpty())
		{	PersonyzeTracker.inst.reportActionStatus(id, "product", productId);
		}
	}

	public void reportArticleClick(String articleId)
	{	if (!articleId.isEmpty())
		{	PersonyzeTracker.inst.reportActionStatus(id, "article", articleId);
		}
	}

	public void reportError(String message)
	{	PersonyzeTracker.inst.reportActionStatus(id, "error", message);
	}
}
