package com.personyze.androidsdk;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.CRC32;

/**
 * <p>
 *     This is the main class in this API.
 *     It's singleton. You need to call the methods of it's default instance (PersonyzeTracker.inst) to perform actions on remote Personyze server.
 * </p>
 * <p>
 *     The first thing you probably want to do is {@link #navigate(String)} to some "document".
 *     The Document is abstract entity in your application. For example there can be a Home page, a Category page,
 *     a Product page, a Checkout page, and so. You can use activity names as document names.
 * </p>
 * <p>
 *     See the methods of this object to full list of available operations.
 * </p>
 * <p>
 *     After sequence of calls, you can {@link #getResult(AsyncResult)} to see what Campaigns become matching
 *     and what content to present in your application.
 * </p>
 * <p>
 *     If you don't need the result, so don't call {@link #getResult(AsyncResult)}, call {@link #done()} after you're done.
 *     This sends pending requests to Personyze server.
 * </p>
 */
public class PersonyzeTracker
{	private static final String GATEWAY_URL = "https://app.personyze.com/rest/";
	static final String WEB_VIEW_LIB_URL = "https://counter.personyze.com/web-view.js";
	static final String LIBS_URL = "https://counter.personyze.com/actions/webkit/";
	static final String WEBVIEW_BASE_URL = "https://counter.personyze.com/";
	private static final String USER_AGENT = "Personyze Android SDK/1.0";
	private static final String PLATFORM = "Android";
	private static final int POST_LIMIT = 50000;
	private static final int REMEMBER_PAST_SESSIONS = 12;

	enum Rejected
	{	DONT_SHOW_AGAIN, PRESENTING_RULES
	}

	private boolean isInitialized;
	private Context context;
	private String apiKey;
	private String httpAuth;
	private int userId;
	private RequestQueue requestQueue;
	private SharedPreferences storage;
	private double timeZone;
	private String language;
	private String os;
	private String deviceType;
	private final ArrayList<String[]> commands = new ArrayList<>(4);
	private boolean isNavigate = false;
	private boolean wantNewSession;
	private String sessionId;
	private int cacheVersion;
	private int apiKeyHash;
	private PersonyzeResult personyzeResult;
	private AsyncResultSplit<PersonyzeResult> queryingResults;
	private StoredIntMap blockedActions;
	private PastSessions pastSessions;

	// Singleton
	public static final PersonyzeTracker inst = new PersonyzeTracker();
	private PersonyzeTracker() {}

	private static class AsyncResultSplit<T> implements AsyncResult<T>
	{	private final ArrayList<AsyncResult<T>> queue;
		private final int nTimes;
		private int n;
		private PersonyzeError error;

		public AsyncResultSplit(int nTimes, AsyncResult<T> asyncResult)
		{	this.nTimes = nTimes;
			queue = new ArrayList<>(1);
			add(asyncResult);
		}

		void add(AsyncResult<T> asyncResult)
		{	if (asyncResult != null)
			{	queue.add(asyncResult);
			}
		}

		@Override public void success(T result)
		{	if (++n == nTimes)
			{	for (AsyncResult<T> r : queue)
				{	if (error != null)
					{	r.error(error);
					}
					else
					{	r.success(result);
					}
				}
				if (error!=null && queue.size()==0)
				{	Log.e("Personyze", error.getLocalizedMessage());
				}
			}
		}

		@Override public void error(PersonyzeError error)
		{	this.error = error;
			success(null);
		}
	}

	public interface AsyncResult<T>
	{	void success(T result);
		void error(PersonyzeError error);
	}

	public interface Async<T>
	{	void callback(T value);
	}

	private class StoredIntMap extends HashMap<Integer, Integer>
	{	private final String key;

		StoredIntMap(String key)
		{	this.key = key;
			String str = storage.getString(key, null);
			if (str != null)
			{	for (String kv : str.split(","))
				{	String[] k_v = kv.split(":");
					if (k_v.length == 2)
					{	int k = intVal(k_v[0]);
						int v = intVal(k_v[1]);
						if (k>0 && v>0)
						{	put(k, v);
						}
					}
				}
			}
		}

		void save()
		{	StringBuilder sb = new StringBuilder(size() * 6);
			boolean comma = false;
			for (Map.Entry<Integer, Integer> entry : entrySet())
			{	if (comma)
				{	sb.append(',');
				}
				else
				{	comma = true;
				}
				sb.append(entry.getKey());
				sb.append(':');
				sb.append(entry.getValue());
			}
			SharedPreferences.Editor editor = storage.edit();
			editor.putString(key, sb.toString());
			editor.apply();
		}

		void dec()
		{	for (Iterator<Map.Entry<Integer, Integer>> it = entrySet().iterator(); it.hasNext();)
			{	Map.Entry<Integer, Integer> entry = it.next();
				int value = entry.getValue() - 1;
				if (value <= 0)
				{	it.remove();
				}
				else
				{	entry.setValue(value);
				}
			}
			save();
		}
	}

	private class PastSessions
	{	String value;

		PastSessions()
		{	value = storage.getString("Past Sessions", "");
		}

		void add(int sessionStartTime)
		{	// add
			if (value.isEmpty())
			{	value = ""+sessionStartTime;
			}
			else
			{	String[] arr = value.split(",");
				if (arr.length+1 > REMEMBER_PAST_SESSIONS)
				{	value = value.substring(value.indexOf(',')+1) + "," + sessionStartTime;
				}
				else
				{	value += ","+sessionStartTime;
				}
			}
			// save
			SharedPreferences.Editor editor = storage.edit();
			editor.putString("Past Sessions", value);
			editor.apply();
		}
	}

	private static int getUserId(Context context)
	{   String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
		CRC32 crc = new CRC32();
		crc.update(androidId.getBytes());
		return (int)crc.getValue();
	}

	protected static int intVal(String str)
	{	try
		{	int pos = str.indexOf(' ');
			return Integer.parseInt(pos==-1 ? str : str.substring(0, pos));
		}
		catch (Exception e)
		{	// ignore
		}
		return 0;
	}

	private void httpFetch(String path, final String postData, final AsyncResult<String> asyncResult)
	{	if (apiKey != null)
		{	requestQueue.add
			(	new StringRequest
				(	postData==null ? Request.Method.GET : Request.Method.POST,
					GATEWAY_URL+path,
					new Response.Listener<String>()
					{	@Override public void onResponse(String response)
						{	if (response!=null && response.length()>0)
							{	asyncResult.success(response);
							}
							else
							{	asyncResult.error(null);
							}
						}
					},
					new Response.ErrorListener()
					{	@Override public void onErrorResponse(VolleyError error)
						{	String message = null;
							PersonyzeError.Type type = PersonyzeError.Type.OTHER;
							if (error.networkResponse != null)
							{	if (error.networkResponse.statusCode == 500)
								{	type = PersonyzeError.Type.HTTP_500;
									try
									{	message = new String(error.networkResponse.data, "utf-8");
									}
									catch (UnsupportedEncodingException e)
									{	// not interesting
									}
								}
								else if (error.networkResponse.statusCode == 503)
								{	type = PersonyzeError.Type.HTTP_503;
									message = "Service temporarily unavailable";
								}
								else if (error.networkResponse.statusCode == 401)
								{	type = PersonyzeError.Type.HTTP_401;
									message = "Invalid API key";
								}
							}
							if (message == null)
							{	message = error.getLocalizedMessage();
								if (message == null)
								{	message = "HTTP request failed";
								}
							}
							asyncResult.error(new PersonyzeError(message, type));
						}
					}
				)
				{	@Override public String getBodyContentType()
					{	return "application/json; charset=utf-8";
					}

					@Override public byte[] getBody() throws AuthFailureError
					{	if (postData != null)
						{	try
							{	return postData.getBytes("utf-8");
							}
							catch (UnsupportedEncodingException e)
							{	throw new AuthFailureError("Encoding problem");
							}
						}
						return null;
					}

					@Override public Map<String, String> getHeaders()
					{	HashMap<String, String> params = new HashMap<>();
						params.put("Authorization", httpAuth);
						params.put("User-Agent", USER_AGENT);
						return params;
					}
				}
			);
		}
		else
		{	asyncResult.error(new PersonyzeError("PersonyzeTracker not initialized"));
		}
	}

	private void addCommand(String arg1)
	{	String[] command = new String[1];
		command[0] = arg1;
		commands.add(command);
	}

	private void addCommand(String arg1, String arg2)
	{	String[] command = new String[2];
		command[0] = arg1;
		command[1] = arg2;
		commands.add(command);
	}

	private void addCommand(String arg1, String arg2, String arg3)
	{	String[] command = new String[3];
		command[0] = arg1;
		command[1] = arg2;
		command[2] = arg3;
		commands.add(command);
	}

	private void addCommand(String arg1, String arg2, String arg3, String arg4)
	{	String[] command = new String[4];
		command[0] = arg1;
		command[1] = arg2;
		command[2] = arg3;
		command[3] = arg4;
		commands.add(command);
	}

	private void flush(boolean requireSomeResult, boolean isStartNewSession, final AsyncResult<PersonyzeResult> asyncResult)
	{	PersonyzeError returnError = null;
		PersonyzeResult returnPersonyzeResult = null;
		synchronized (this)
		{	try
			{	doInitialize();
				if (commands.size()>0 || requireSomeResult && personyzeResult==null)
				{	// Form the POST request that includes session data and commands
					JSONStringer postJson = new JSONStringer();
					postJson.object();
					postJson.key("user_id").value(userId);
					postJson.key("session_id").value(sessionId);
					postJson.key("new_session").value(wantNewSession || sessionId==null || intVal(sessionId)+90*60-5 <= System.currentTimeMillis()/1000); // sessionId contains information that Personyze server wants me to store and send him back. The only thing he promises me is that there is sessionStartTime in the beginning
					postJson.key("past_sessions").value(pastSessions.value);
					postJson.key("platform").value(PLATFORM);
					postJson.key("time_zone").value(timeZone);
					postJson.key("languages").value(language);
					DisplayMetrics m = Resources.getSystem().getDisplayMetrics();
					postJson.key("screen").value(String.format(Locale.US, "%dx%d", m.widthPixels, m.heightPixels));
					postJson.key("os").value(os);
					postJson.key("device_type").value(deviceType);
					postJson.key("commands").array();
					for (String[] command : commands)
					{	postJson.array();
						for (String arg : command)
						{	postJson.value(arg);
						}
						postJson.endArray();
					}
					postJson.endArray();
					postJson.endObject();
					String postStr = postJson.toString();
					commands.clear(); // delete commands that are about to be sent (to avoid sending twice)
					if (postStr.length() > POST_LIMIT)
					{	returnError = new PersonyzeError("Request was too big", PersonyzeError.Type.REQUEST_TOO_BIG);
					}
					else
					{	final boolean curIsNavigate = isNavigate;
						isNavigate = false;
						// Further getResult() must use new object
						final AsyncResultSplit<PersonyzeResult> asyncResults = new AsyncResultSplit<>(1, asyncResult);
						queryingResults = asyncResults;
						wantNewSession = false;
						// Send the request
						httpFetch
						(	"tracker-v1",
							postStr,
							new AsyncResult<String>()
							{	@Override public void success(String text)
								{	try
									{	JSONObject object = (JSONObject)new JSONTokener(text).nextValue();
										String rSessionId = object.isNull("session_id") ? null : object.getString("session_id");
										int rCacheVersion = object.getInt("cache_version");
										JSONArray rConditions = object.getJSONArray("conditions");
										JSONArray rActions = object.getJSONArray("actions");
										JSONArray rDismissConditions = object.getJSONArray("dismiss_conditions");
										JSONArray rDismissActions = object.getJSONArray("dismiss_actions");
										// vars
										boolean wantClearCache;
										boolean loadConditions = false;
										boolean loadActions = false;
										PersonyzeResult newPersonyzeResult = new PersonyzeResult();
										boolean hasCommandsAdded = false;
										synchronized (PersonyzeTracker.this)
										{	if (rSessionId == null)
											{	rSessionId = sessionId;
											}
											if (rCacheVersion == 0)
											{	rCacheVersion = cacheVersion;
											}
											wantClearCache = rCacheVersion > cacheVersion;
											cacheVersion = rCacheVersion;
											int rSessionStartTime = intVal(rSessionId);
											boolean isNewSession = rSessionStartTime != intVal(sessionId);
											sessionId = rSessionId;
											if (isNewSession)
											{	blockedActions.dec();
												pastSessions.add(rSessionStartTime);
											}
											if (wantClearCache)
											{	clearCache(); // sets editor.putString("User", sessionId)
											}
											else if (isNewSession)
											{	SharedPreferences.Editor editor = storage.edit();
												editor.putString("User", rSessionId);
												editor.remove("New Session");
												editor.apply();
											}
											// newPersonyzeResult.conditions
											newPersonyzeResult.conditions = new ArrayList<>(rConditions.length());
											for (int i=0, i_end=rConditions.length(); i<i_end; i++)
											{	object = rConditions.getJSONObject(i);
												PersonyzeCondition condition = new PersonyzeCondition(object.getInt("id"));
												if (!wantClearCache && !condition.fromStorage(storage))
												{	loadConditions = true;
												}
												newPersonyzeResult.conditions.add(condition);
											}
											// newPersonyzeResult.actions
											newPersonyzeResult.actions = new ArrayList<>(rActions.length());
											for (int i=0, i_end=rActions.length(); i<i_end; i++)
											{	object = rActions.getJSONObject(i);
												int id = object.getInt("id");
												if (!blockedActions.containsKey(id))
												{	HashMap<String, String> data = null;
													JSONObject jData = object.optJSONObject("data");
													if (jData != null)
													{	data = new HashMap<>();
														Iterator<String> keys = jData.keys();
														while (keys.hasNext())
														{	String key = keys.next();
															data.put(key, jData.getString(key));
														}
													}
													PersonyzeAction action = new PersonyzeAction(id, data, rCacheVersion);
													if (!wantClearCache && !action.fromStorage(storage))
													{	loadActions = true;
													}
													newPersonyzeResult.actions.add(action);
													// store data, so it will survive application/activity restart
													try
													{	action.dataToStorage(context);
													}
													catch (IOException e)
													{	Log.e("Personyze", e.getLocalizedMessage());
													}
												}
												else
												{	addCommand("Action Status", ""+id, "dont-show");
													hasCommandsAdded = true;
												}
											}
										}
										// dismissConditions
										int[] dismissConditions = new int[rDismissConditions.length()];
										for (int i=0, i_end=rDismissConditions.length(); i<i_end; i++)
										{	dismissConditions[i] = rDismissConditions.getInt(i);
										}
										// dismissActions
										int[] dismissActions = new int[rDismissActions.length()];
										for (int i=0, i_end=rDismissActions.length(); i<i_end; i++)
										{	dismissActions[i] = rDismissActions.getInt(i);
										}
										// done
										loadWhatNeededThenSetResult(newPersonyzeResult, curIsNavigate, dismissConditions, dismissActions, loadConditions || wantClearCache && newPersonyzeResult.conditions.size()>0, loadActions || wantClearCache && newPersonyzeResult.actions.size()>0, wantClearCache, asyncResults);
										if (hasCommandsAdded)
										{	flush(false, false, null);
										}
									}
									catch (JSONException e)
									{	queryingResults = null;
										asyncResults.error(new PersonyzeError("JSON error: "+e.getLocalizedMessage()));
									}
									catch (PersonyzeError error)
									{	queryingResults = null;
										asyncResults.error(error);
									}
								}

								@Override public void error(PersonyzeError error)
								{	queryingResults = null;
									asyncResults.error(error);
								}
							}
						);
					}
				}
				else if (asyncResult != null)
				{	// Nothing to send, just get current result
					if (queryingResults != null)
					{	queryingResults.add(asyncResult);
					}
					else
					{	returnPersonyzeResult = personyzeResult;
					}
				}
			}
			catch (JSONException e)
			{	commands.clear(); // discard failed commands
				isNavigate = false;
				returnError = new PersonyzeError("JSON error: "+e.getLocalizedMessage());
			}
			catch (PersonyzeError e)
			{	returnError = e;
			}
			if (isStartNewSession)
			{	wantNewSession = true;
				personyzeResult = null;
				SharedPreferences.Editor editor = storage.edit();
				editor.putBoolean("New Session", true);
				editor.remove("Conditions");
				editor.remove("Actions");
				editor.apply();
			}
		}
		// now not synchronized
		if (returnError != null)
		{	if (asyncResult != null)
			{	asyncResult.error(returnError);
			}
			else
			{	Log.e("Personyze", returnError.getLocalizedMessage());
			}
		}
		else if (returnPersonyzeResult != null)
		{	asyncResult.success(returnPersonyzeResult);
		}
	}

	private void setResult(PersonyzeResult newPersonyzeResult, boolean curIsNavigate, int[] dismissConditions, int[] dismissActions)
	{	if (curIsNavigate || personyzeResult==null)
		{	personyzeResult = newPersonyzeResult;
		}
		else
		{	// merge with new result
			for (PersonyzeCondition item : newPersonyzeResult.conditions)
			{	int pos = personyzeResult.conditions.indexOf(item);
				if (pos == -1)
				{	personyzeResult.conditions.add(item);
				}
			}
			for (PersonyzeAction item : newPersonyzeResult.actions)
			{	int pos = personyzeResult.actions.indexOf(item);
				if (pos == -1)
				{	personyzeResult.actions.add(item);
				}
			}
			for (int item : dismissConditions)
			{	int pos = personyzeResult.conditions.indexOf(new PersonyzeCondition(item));
				if (pos != -1)
				{	personyzeResult.conditions.remove(pos);
				}
			}
			for (int item : dismissActions)
			{	int pos = personyzeResult.actions.indexOf(new PersonyzeAction(item));
				if (pos != -1)
				{	personyzeResult.actions.remove(pos);
				}
			}
		}
		personyzeResult.toStorage(storage);
	}

	private void loadWhatNeededThenSetResult(final PersonyzeResult newPersonyzeResult, final boolean curIsNavigate, final int[] dismissConditions, final int[] dismissActions, boolean loadConditions, boolean loadActions, final boolean noTryCache, final AsyncResult<PersonyzeResult> asyncResult)
	{	if (!loadConditions && !loadActions)
		{	// done
			PersonyzeResult returnPersonyzeResult;
			synchronized (this)
			{	setResult(newPersonyzeResult, curIsNavigate, dismissConditions, dismissActions);
				queryingResults = null;
				returnPersonyzeResult = personyzeResult;
			}
			asyncResult.success(returnPersonyzeResult);
		}
		else
		{	// need to load conditions/actions or report rejected action
			final AsyncResultSplit<PersonyzeResult> asyncResultSplit = new AsyncResultSplit<>
			(	loadConditions  && loadActions ? 2 : 1,
				new AsyncResult<PersonyzeResult>()
				{	@Override public void success(PersonyzeResult result)
					{	PersonyzeResult returnPersonyzeResult;
						synchronized (PersonyzeTracker.this)
						{	setResult(newPersonyzeResult, curIsNavigate, dismissConditions, dismissActions);
							queryingResults = null;
							returnPersonyzeResult = personyzeResult;
						}
						asyncResult.success(returnPersonyzeResult);
					}

					@Override public void error(PersonyzeError error)
					{	synchronized (PersonyzeTracker.this)
						{	queryingResults = null;
						}
						asyncResult.error(error);
					}
				}
			);
			StringBuilder sb = new StringBuilder(200);
			if (loadConditions)
			{	sb.append("conditions/columns/id,name/where/id");
				char delim = ':';
				for (PersonyzeCondition condition : newPersonyzeResult.conditions)
				{	if (noTryCache || condition.name==null)
					{	sb.append(delim);
						sb.append(condition.id);
						delim = ',';
					}
				}
				httpFetch
				(	sb.toString(),
					null,
					new PersonyzeTracker.AsyncResult<String>()
					{	@Override public void success(String text)
						{	try
							{	JSONArray array = (JSONArray)new JSONTokener(text).nextValue();
								for (int i=0, i_end=array.length(); i<i_end; i++)
								{	JSONObject row = array.getJSONObject(i);
									int id = row.getInt("id");
									String name = row.getString("name");
									for (PersonyzeCondition condition : newPersonyzeResult.conditions)
									{	if (condition.id == id)
										{	condition.name = name;
											condition.toStorage(storage);
											break;
										}
									}
								}
								asyncResultSplit.success(newPersonyzeResult);
							}
							catch (JSONException e)
							{	asyncResultSplit.error(new PersonyzeError("JSON error: "+e.getLocalizedMessage()));
							}
						}

						@Override public void error(PersonyzeError error)
						{	asyncResultSplit.error(error);
						}
					}
				);
			}
			if (loadActions)
			{	sb.setLength(0);
				sb.append("actions/columns/id,name,content_type,content_param,content_begin,content_end,libs_app,placeholders/where/id");
				char delim = ':';
				for (PersonyzeAction action : newPersonyzeResult.actions)
				{	if (noTryCache || action.name==null)
					{	sb.append(delim);
						sb.append(action.id);
						delim = ',';
					}
				}
				httpFetch
				(	sb.toString(),
					null,
					new PersonyzeTracker.AsyncResult<String>()
					{	@Override public void success(String text)
						{	try
							{	StringBuilder loadPlaceholders = null;
								JSONArray array = (JSONArray)new JSONTokener(text).nextValue();
								for (int i=0, i_end=array.length(); i<i_end; i++)
								{	JSONObject row = array.getJSONObject(i);
									int id = row.getInt("id");
									String name = row.getString("name");
									String contentType = row.isNull("content_type") ? "" : row.getString("content_type");
									String contentParam = row.isNull("content_param") ? "" : row.getString("content_param");
									String contentBegin = row.isNull("content_begin") ? "" : row.getString("content_begin");
									String contentEnd = row.isNull("content_end") ? "" : row.getString("content_end");
									String libsApp = row.isNull("libs_app") ? "" : row.getString("libs_app");
									JSONArray placeholders = row.getJSONArray("placeholders");
									for (PersonyzeAction action : newPersonyzeResult.actions)
									{	if (action.id == id)
										{	action.name = name;
											action.contentType = contentType;
											action.contentParam = contentParam;
											action.contentBegin = contentBegin;
											action.contentEnd = contentEnd;
											action.libsApp = libsApp;
											action.placeholders = new ArrayList<>(placeholders.length());
											for (int j=0, j_end=placeholders.length(); j<j_end; j++)
											{	PersonyzePlaceholder placeholder = new PersonyzePlaceholder(placeholders.getInt(j));
												if (!noTryCache && !placeholder.fromStorage(storage))
												{	if (loadPlaceholders == null)
													{	loadPlaceholders = new StringBuilder(128);
														loadPlaceholders.append("placeholders/columns/id,name,html_id,units_count_max/where/id:");
														loadPlaceholders.append(placeholder.id);
													}
													else
													{	loadPlaceholders.append(',');
														loadPlaceholders.append(placeholder.id);
													}
												}
												action.placeholders.add(placeholder);
											}
											action.toStorage(storage);
											break;
										}
									}
								}
								if (loadPlaceholders == null)
								{	asyncResultSplit.success(newPersonyzeResult);
								}
								else
								{	httpFetch
									(	loadPlaceholders.toString(),
										null,
										new PersonyzeTracker.AsyncResult<String>()
										{	@Override public void success(String text)
											{	try
												{	JSONArray array = (JSONArray)new JSONTokener(text).nextValue();
													for (int i=0, i_end=array.length(); i<i_end; i++)
													{	JSONObject row = array.getJSONObject(i);
														int id = row.getInt("id");
														String name = row.getString("name");
														String htmlId = row.isNull("html_id") ? "" : row.getString("html_id");
														int unitsCountMax = row.getInt("units_count_max");
														for (PersonyzeAction action : newPersonyzeResult.actions)
														{	if (action.placeholders != null)
															{	for (PersonyzePlaceholder placeholder : action.placeholders)
																{	if (placeholder.id == id)
																	{	placeholder.name = name;
																		placeholder.htmlId = htmlId;
																		placeholder.unitsCountMax = unitsCountMax;
																		placeholder.toStorage(storage);
																		break;
																	}
																}
															}
														}
													}
													asyncResultSplit.success(newPersonyzeResult);
												}
												catch (JSONException e)
												{	asyncResultSplit.error(new PersonyzeError("JSON error: "+e.getLocalizedMessage()));
												}
											}

											@Override public void error(PersonyzeError error)
											{	asyncResultSplit.error(error);
											}
										}
									);
								}
							}
							catch (JSONException e)
							{	asyncResultSplit.error(new PersonyzeError("JSON error: "+e.getLocalizedMessage()));
							}
						}

						@Override public void error(PersonyzeError error)
						{	asyncResultSplit.error(error);
						}
					}
				);
			}
		}
	}

	/**
	 * Send Action Status to Personyze. When you show executed actions, you can report that user clicked on a click target (button), or closed that action.
	 * @param actionId The action ID. Get it from PersonyzeAction object, from "id" property.
	 * @param status One of: "target", "close", "product", "article" or "error". 1. "target" means user clicked the destination button. In this case "arg" will be ignored. 2. "close" - user chosen to dismiss this action. "arg" is number of sessions not to show again. 3. "product" - user clicked on a product in a recommendation widget. "arg" is product internal ID. 4. "article" is like "product". 5. "error" - you rejected to show this action to user. "arg" is reason message (will appear in visits dashboard).
	 * @param arg See "status".
	 */
	void reportActionStatus(int actionId, String status, String arg)
	{	if (actionId>0 && status!=null && status.length()>0)
		{	synchronized (this)
			{	String actionIdStr = ""+actionId;
				// Already?
				for (int i=commands.size()-1; i>=0; i--)
				{	String[] command = commands.get(i);
					if (command.length==2 && command[0].equals("Navigate"))
					{	break;
					}
					if (command.length==4 && command[1].equals(actionIdStr) && command[0].equals("Action Status"))
					{	if (status.equals("executed") || status.equals(command[2]))
						{	return; // yes, already reported
						}
					}
				}
				// Report
				addCommand("Action Status", actionIdStr, status, arg);
				if (status.equals("close"))
				{	int nSessions = intVal(arg);
					if (nSessions > 0)
					{	blockedActions.put(actionId, nSessions);
						blockedActions.save();
					}
				}
			}
			flush(false, false, null);
		}
	}

	private void doInitialize() throws PersonyzeError
	{	if (!isInitialized)
		{	if (context == null)
			{	throw new PersonyzeError("No context given");
			}
			if (apiKey==null || apiKey.length()!=40)
			{	throw new PersonyzeError("API Key must be 40 characters", PersonyzeError.Type.MALFORMED_API_KEY);
			}
			String creds = String.format("api:%s", apiKey);
			httpAuth = "Basic " + Base64.encodeToString(creds.getBytes(), Base64.DEFAULT);
			userId = getUserId(context);
			requestQueue = Volley.newRequestQueue(context);
			storage = context.getSharedPreferences("Personyze Tracker", Context.MODE_PRIVATE);
			timeZone = TimeZone.getDefault().getRawOffset()/(60*60*1000.0);
			language = context.getResources().getConfiguration().locale.getLanguage();
			os = String.format("Android/%s (%s)", Build.VERSION.RELEASE, Build.VERSION.CODENAME);
			if
			(	(context.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK)
				>=
				Configuration.SCREENLAYOUT_SIZE_LARGE
			)
			{	deviceType = "tablet";
			}
			else
			{	deviceType = "phone";
			}
			blockedActions = new StoredIntMap("Blocked Actions");
			pastSessions = new PastSessions();
			apiKeyHash = apiKey.hashCode();
			personyzeResult = null;
			isInitialized = true;
			// restore current state
			wantNewSession = storage.getBoolean("New Session", false);
			sessionId = storage.getString("User", null);
			cacheVersion = storage.getInt("Cache Version", 0);
			if (storage.getInt("Api Key Hash", 0) != apiKeyHash)
			{	clearCache(); // delete cached conditions and actions from (possible) different account
			}
			PersonyzeResult tr = new PersonyzeResult();
			if (tr.fromStorage(storage, context))
			{	personyzeResult = tr;
			}
		}
	}

	// MARK: public

	/**
	 * Initialize the tracker. This is required before calling any other methods. Typically you need to call this once. Second call with the same apiKey will do nothing.
	 * @param context The context of your application (usually an Activity).
	 * @param apiKey Your personal secret key, obtained in the Personyze account.
	 */
	public synchronized void initialize(Context context, String apiKey)
	{	if (this.apiKey==null || !this.apiKey.equals(apiKey))
		{	this.isInitialized = false;
			this.context = context==null ? null : context.getApplicationContext();
			this.apiKey = apiKey;
		}
	}

	/**
     * Send navigation event to Personyze. This is equivalent to a page view on your site.
     * @param documentName Document identifier, that represents navigation within your app. You can use any name, e.g. "Cart page".
     */
	public void navigate(String documentName)
	{	if (documentName!=null && !documentName.isEmpty())
		{	synchronized (this)
			{	addCommand("Navigate", "urn:personyze:doc:"+documentName);
				isNavigate = true;
			}
		}
	}

	/**
	 * Send user profile data to Personyze, like email.
	 * @param field Profile field. Can be any identifier.
	 * @param value The value you want to send.
	 */
	public void logUserData(String field, String value)
	{	if (field!=null && !field.isEmpty())
		{	synchronized (this)
			{	addCommand("User profile", field, value);
			}
		}
	}

	/**
	 * Send "Product Viewed" event to Personyze.
	 * @param productId The product ID is what appears in your products catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FProducts%20catalog" target="_blank">here</a>.
	 */
	public void productViewed(String productId)
	{	if (productId!=null && !productId.isEmpty())
		{	synchronized (this)
			{	addCommand("Product Viewed", productId);
			}
		}
	}

	/**
	 * Send "Product Added to cart" event to Personyze.
	 * @param productId The product ID is what appears in your products catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FProducts%20catalog" target="_blank">here</a>.
	 */
	public void productAddedToCart(String productId)
	{	if (productId!=null && !productId.isEmpty())
		{	synchronized (this)
			{	addCommand("Product Added to cart", productId);
			}
		}
	}

	/**
	 * Send "Product Liked" ("Added to favorites") event to Personyze.
	 * @param productId The product ID is what appears in your products catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FProducts%20catalog" target="_blank">here</a>.
	 */
	public void productLiked(String productId)
	{	if (productId!=null && !productId.isEmpty())
		{	synchronized (this)
			{	addCommand("Product Liked", productId);
			}
		}
	}

	/**
	 * Send "Product Purchased" event to Personyze.
	 * @param productId The product ID is what appears in your products catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FProducts%20catalog" target="_blank">here</a>.
	 */
	public void productPurchased(String productId)
	{	if (productId!=null && !productId.isEmpty())
		{	synchronized (this)
			{	addCommand("Product Purchased", productId);
			}
		}
	}

	/**
	 * Send "Product Unliked" ("Removed from favorites") event to Personyze.
	 * @param productId The product ID is what appears in your products catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FProducts%20catalog" target="_blank">here</a>.
	 */
	public void productUnliked(String productId)
	{	if (productId!=null && !productId.isEmpty())
		{	synchronized (this)
			{	addCommand("Product Unliked", productId);
			}
		}
	}

	/**
	 * Send "Product Removed from cart" event to Personyze.
	 * @param productId The product ID is what appears in your products catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FProducts%20catalog" target="_blank">here</a>.
	 */
	public void productRemovedFromCart(String productId)
	{	if (productId!=null && !productId.isEmpty())
		{	synchronized (this)
			{	addCommand("Product Removed from cart", productId);
			}
		}
	}

	/**
	 * Send "Products Purchased" event to Personyze. This event converts all the products that were Added to cart to Purchased.
	 */
	public synchronized void productsPurchased()
	{	addCommand("Products Purchased");
	}

	/**
	 * Send "Products Unliked" event to Personyze. This event converts all the products that were Liked to Viewed.
	 */
	public synchronized void productsUnliked()
	{	addCommand("Products Unliked");
	}

	/**
	 * Send "Products Removed from cart" event to Personyze. This event converts all the products that were Added to cart to Viewed.
	 */
	public synchronized void productsRemovedFromCart()
	{	addCommand("Products Removed from cart");
	}

	/**
	 * Send "Article Viewed" event to Personyze.
	 * @param articleId The article ID is what appears in your articles catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FArticles%20catalog" target="_blank">here</a>.
	 */
	public void articleViewed(String articleId)
	{	if (articleId!=null && !articleId.isEmpty())
		{	synchronized (this)
			{	addCommand("Article Viewed", articleId);
			}
		}
	}

	/**
	 * Send "Article Liked" (added to favorites) event to Personyze.
	 * @param articleId The article ID is what appears in your articles catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FArticles%20catalog" target="_blank">here</a>.
	 */
	public void articleLiked(String articleId)
	{	if (articleId!=null && !articleId.isEmpty())
		{	synchronized (this)
			{	addCommand("Article Liked", articleId);
			}
		}
	}

	/**
	 * Send "Article Commented" event to Personyze.
	 * @param articleId The article ID is what appears in your articles catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FArticles%20catalog" target="_blank">here</a>.
	 */
	public void articleCommented(String articleId)
	{	if (articleId!=null && !articleId.isEmpty())
		{	synchronized (this)
			{	addCommand("Article Commented", articleId);
			}
		}
	}

	/**
	 * Send "Article Unliked" (removed from favorites) event to Personyze.
	 * @param articleId The article ID is what appears in your articles catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FArticles%20catalog" target="_blank">here</a>.
	 */
	public void articleUnliked(String articleId)
	{	if (articleId!=null && !articleId.isEmpty())
		{	synchronized (this)
			{	addCommand("Article Unliked", articleId);
			}
		}
	}

	/**
	 * Send "Article Reached Goal" event to Personyze.
	 * @param articleId The article ID is what appears in your articles catalog uploaded to Personyze, in the "Internal ID" column.
	 *                  You can see your catalog <a href="https://personyze.com/site/tracker/condition/index#cat=Account%20settings%2FRecommendations%2FArticles%20catalog" target="_blank">here</a>.
	 */
	public void articleGoal(String articleId)
	{	if (articleId!=null && !articleId.isEmpty())
		{	synchronized (this)
			{	addCommand("Article Goal", articleId);
			}
		}
	}

	/**
	 * When you call action.renderOnWebView(), it generates click events when user taps goal/close buttons in action HTML. If your application processes that events (e.g. closes action), you need to report this to Personyze, so you will have CTR and close-rate statistics.
	 * @param clicked Object that action.renderOnWebView() gives you.
	 */
	public void reportActionClicked(PersonyzeAction.Clicked clicked)
	{	if (clicked != null)
		{	reportActionStatus(clicked.actionId, clicked.status, clicked.arg);
		}
	}

	/**
	 * What conditions are matching, and what actions are to be presented. This will send pending events to Personyze. This library remembers (stores to memory) the result, and until you call startNewSession(), you can get current result, even after object recreation.
	 * @param asyncResult Callback.
	 */
	public void getResult(final AsyncResult<PersonyzeResult> asyncResult)
	{	flush(true, false, asyncResult);
	}

	/**
	 * This asks Personyze to start new session for current user. For each user Personyze counts number of sessions.
	 * Call this when user closes and reopens the application. Each session lasts not more than 1.5 hours, so it will
	 * restart after this period automatically.
	 */
	public void startNewSession()
	{	flush(false, true, null);
	}

	/**
	 * Call this at last. This method sends pending events to Personyze.
	 */
	public void done()
	{	flush(false, false, null);
	}

	/**
	 * Normally you don't need to call this.
	 */
	public synchronized void clearCache() throws PersonyzeError
	{	doInitialize();
		if (storage == null)
		{	throw new PersonyzeError("PersonyzeTracker not initialized");
		}
		Set<String> conditions = storage.getStringSet("Conditions", null);
		Set<String> actions = storage.getStringSet("Actions", null);
		SharedPreferences.Editor editor = storage.edit();
		editor.clear();
		// the following settings don't depend on cacheVersion
		editor.putInt("Api Key Hash", apiKeyHash);
		if (wantNewSession)
		{	editor.putBoolean("New Session", true);
		}
		if (sessionId != null)
		{	editor.putString("User", sessionId);
		}
		if (cacheVersion != 0)
		{	editor.putInt("Cache Version", cacheVersion);
		}
		if (conditions != null)
		{	editor.putStringSet("Conditions", conditions);
		}
		if (actions != null)
		{	editor.putStringSet("Actions", actions);
		}
		editor.apply();
	}
}
