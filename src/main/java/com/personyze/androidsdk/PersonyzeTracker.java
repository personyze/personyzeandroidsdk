package com.personyze.androidsdk;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.json.JSONTokener;

/**
 * <p>
 *     This is the main class in this API.
 *     It's singleton. You need to call the methods of it's default instance (PersonyzeTracker.inst) to perform actions on remote Personyze server.
 * </p>
 * <p>
 *     The first thing you probably want to do is {@link #navigate(String)} to some "document".
 *     The Document is abstract entity in your application. For example there can be a Home page, a Category page,
 *     a Product page, a Checkout page, and so on. You can use activity names as document names.
 * </p>
 * <p>
 *     See the methods of this object to full list of available operations.
 * </p>
 * <p>
 *     After sequence of calls, you can {@link #getResult(Context)} to see what Campaigns become matching
 *     and what content to present in your application.
 * </p>
 * <p>
 *     If you don't need the result, so don't call {@link #getResult(Context)}, call {@link #done(Context)} after you're done.
 *     This sends pending requests to Personyze server.
 * </p>
 */
public class PersonyzeTracker
{	static final String GATEWAY_URL = "https://app.personyze.com/rest/";
	static final String WEB_VIEW_LIB_URL = "https://counter.personyze.com/web-view.js";
	static final String LIBS_URL = "https://counter.personyze.com/actions/webkit/";
	static final String WEBVIEW_BASE_URL = "https://counter.personyze.com/";
	static final String USER_AGENT = "Personyze Android SDK/1.0";
	static final String NOTI_CHANNEL_ID = "Personyze Notifications";
	private static final String NOTI_CHANNEL_NAME = "Recommendations";
	private static  final int NOTI_ID = 1839852125; // random number
	private static final String PLATFORM = "Android";
	private static final int POST_LIMIT = 50000;
	private static final int REMEMBER_PAST_SESSIONS = 12;
	private static final long PERIODIC_INTERVAL_MILLIS = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS;

	enum Rejected
	{	DONT_SHOW_AGAIN, PRESENTING_RULES
	}

	private int userId;
	final PersonyzeHttp http = new PersonyzeHttp();
	private SharedPreferences storage;
	private double timeZone;
	private String language;
	private String os;
	private String deviceType;
	private boolean notiEnabled;
	private long notiLastCheckTime;
	private final ArrayList<String[]> commands = new ArrayList<>(8);
	private boolean isNavigate;
	private boolean wantNewSession;
	private String sessionId;
	private int cacheVersion;
	private int apiKeyHash;
	private PersonyzeResult personyzeResult;
	private Task<PersonyzeResult> queryingResults;
	private StoredIntMap blockedActions;
	private PastSessions pastSessions;

	// Singleton
	public static final PersonyzeTracker inst = new PersonyzeTracker();
	private PersonyzeTracker() {}

	public interface Callback<T>
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

	private Task<PersonyzeResult> flush(Context context, boolean requireSomeResult, boolean isStartNewSession)
	{	queryingResults = doInitialize(context).continueWithTask
		(	task ->
			{	if (task.getException() != null)
				{	throw task.getException();
				}
				final boolean curIsNavigate = isNavigate;
				String postStr = null;
				try
				{	if (commands.size()>0 || requireSomeResult && personyzeResult==null)
					{	// Form the POST request that includes session data and commands
						JSONStringer postJson = new JSONStringer();
						postJson.object();
						postJson.key("user_id").value(userId);
						postJson.key("session_id").value(sessionId);
						postJson.key("new_session").value(wantNewSession || sessionId==null || intVal(sessionId)*1000L + 90*60*1000 - 5000 <= System.currentTimeMillis()); // sessionId contains information that Personyze server wants me to store and send him back. The only thing he promises me is that there is sessionStartTime in the beginning
						postJson.key("past_sessions").value(pastSessions.value);
						postJson.key("platform").value(PLATFORM);
						postJson.key("time_zone").value(timeZone);
						postJson.key("languages").value(language);
						DisplayMetrics m = Resources.getSystem().getDisplayMetrics();
						postJson.key("screen").value(String.format(Locale.US, "%dx%d", m.widthPixels, m.heightPixels));
						postJson.key("os").value(os);
						postJson.key("device_type").value(deviceType);
						postJson.key("noti_enabled").value(notiEnabled);
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
						postStr = postJson.toString();
						commands.clear(); // delete commands that are about to be sent (to avoid sending twice)
						if (postStr.length() > POST_LIMIT)
						{	throw new PersonyzeError("Request was too big", PersonyzeError.Type.REQUEST_TOO_BIG);
						}
						isNavigate = false;
						wantNewSession = false;
					}
				}
				catch (JSONException e)
				{	commands.clear(); // discard failed commands
					isNavigate = false;
					final TaskCompletionSource<PersonyzeResult> asyncResult = new TaskCompletionSource<>();
					asyncResult.setException(new PersonyzeError("JSON error: "+e.getLocalizedMessage()));
					return asyncResult.getTask();
				}
				catch (PersonyzeError e)
				{	final TaskCompletionSource<PersonyzeResult> asyncResult = new TaskCompletionSource<>();
					asyncResult.setException(e);
					return asyncResult.getTask();
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
				if (postStr == null)
				{	// Nothing to send, just get current result
					final TaskCompletionSource<PersonyzeResult> asyncResult = new TaskCompletionSource<>();
					asyncResult.setResult(personyzeResult);
					return asyncResult.getTask();
				}
				// Send the request
				return http.post("tracker-v1", postStr).continueWithTask
				(	task2 ->
					{	try
						{	if (task2.getException() != null)
							{	throw task2.getException();
							}
							JSONObject object = (JSONObject)new JSONTokener(task2.getResult()).nextValue();
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
							if (rSessionId == null)
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
							{	clearCache(context); // sets editor.putString("User", sessionId)
							}
							else if (isNewSession)
							{	SharedPreferences.Editor editor = storage.edit();
								editor.putString("User", rSessionId);
								editor.remove("New Session");
								editor.apply();
							}
							// newPersonyzeResult.conditions
							newPersonyzeResult.conditions = new ArrayList<>(rConditions.length());
							for (int i=0, iEnd=rConditions.length(); i<iEnd; i++)
							{	object = rConditions.getJSONObject(i);
								PersonyzeCondition condition = new PersonyzeCondition(object.getInt("id"));
								if (!wantClearCache && !condition.fromStorage(storage))
								{	loadConditions = true;
								}
								newPersonyzeResult.conditions.add(condition);
							}
							// newPersonyzeResult.actions
							newPersonyzeResult.actions = new ArrayList<>(rActions.length());
							for (int i=0, iEnd=rActions.length(); i<iEnd; i++)
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
									{	Log.e("Personyze", Objects.requireNonNull(e.getLocalizedMessage()));
									}
								}
								else
								{	addCommand("Action Status", ""+id, "dont-show");
									hasCommandsAdded = true;
								}
							}
							// dismissConditions
							int[] dismissConditions = new int[rDismissConditions.length()];
							for (int i=0, iEnd=rDismissConditions.length(); i<iEnd; i++)
							{	dismissConditions[i] = rDismissConditions.getInt(i);
							}
							// dismissActions
							int[] dismissActions = new int[rDismissActions.length()];
							for (int i=0, iEnd=rDismissActions.length(); i<iEnd; i++)
							{	dismissActions[i] = rDismissActions.getInt(i);
							}
							// done
							final boolean wantFlush = hasCommandsAdded;
							return loadWhatNeeded(newPersonyzeResult, loadConditions || wantClearCache && newPersonyzeResult.conditions.size()>0, loadActions || wantClearCache && newPersonyzeResult.actions.size()>0, wantClearCache).continueWith
							(	task3 ->
								{	setResult(newPersonyzeResult, curIsNavigate, dismissConditions, dismissActions);
									if (wantFlush)
									{	flush(context, false, false);
									}
									return personyzeResult;
								}
							);
						}
						catch (JSONException e)
						{	throw new PersonyzeError("JSON error: "+e.getLocalizedMessage());
						}
					}
				);
			}
		);
		return queryingResults;
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

	private Task<Void> loadWhatNeeded(final PersonyzeResult newPersonyzeResult, boolean loadConditions, boolean loadActions, final boolean noTryCache)
	{	Task<Void> loadConditionsTask = null;
		Task<Void> loadActionsTask = null;
		StringBuilder sb = null;
		if (loadConditions)
		{	sb = new StringBuilder(200);
			sb.append("conditions/columns/id,name/where/id");
			char delim = ':';
			for (PersonyzeCondition condition : newPersonyzeResult.conditions)
			{	if (noTryCache || condition.name==null)
				{	sb.append(delim);
					sb.append(condition.id);
					delim = ',';
				}
			}
			loadConditionsTask = http.get(sb.toString()).continueWith
			(	task ->
				{	try
					{	if (task.getException() != null)
						{	throw task.getException();
						}
						JSONArray array = (JSONArray)new JSONTokener(task.getResult()).nextValue();
						for (int i=0, iEnd=array.length(); i<iEnd; i++)
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
					}
					catch (JSONException e)
					{	throw new PersonyzeError("JSON error: "+e.getLocalizedMessage());
					}
					return null;
				}
			);
		}
		if (loadActions)
		{	if (sb == null)
			{	sb = new StringBuilder(200);
			}
			else
			{	sb.setLength(0);
			}
			sb.append("actions/columns/id,name,content_type,content_param,content_begin,content_end,libs_app,placeholders/where/id");
			char delim = ':';
			for (PersonyzeAction action : newPersonyzeResult.actions)
			{	if (noTryCache || action.name==null)
				{	sb.append(delim);
					sb.append(action.id);
					delim = ',';
				}
			}
			loadActionsTask = http.get(sb.toString()).continueWithTask
			(	task ->
				{	try
					{	if (task.getException() != null)
						{	throw task.getException();
						}
						StringBuilder loadPlaceholders = null;
						JSONArray array = (JSONArray)new JSONTokener(task.getResult()).nextValue();
						for (int i=0, iEnd=array.length(); i<iEnd; i++)
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
						if (loadPlaceholders != null)
						{	return http.get(loadPlaceholders.toString()).continueWith
							(	task2 ->
								{	try
									{	if (task2.getException() != null)
										{	throw task2.getException();
										}
										JSONArray array2 = (JSONArray)new JSONTokener(task2.getResult()).nextValue();
										for (int i=0, iEnd=array2.length(); i<iEnd; i++)
										{	JSONObject row = array2.getJSONObject(i);
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
									}
									catch (JSONException e)
									{	throw new PersonyzeError("JSON error: "+e.getLocalizedMessage());
									}
									return null;
								}
							);
						}
					}
					catch (JSONException e)
					{	throw new PersonyzeError("JSON error: "+e.getLocalizedMessage());
					}
					return null;
				}
			);
		}
		if (loadConditionsTask!=null && loadActionsTask==null)
		{	return loadConditionsTask;
		}
		if (loadConditionsTask==null && loadActionsTask!=null)
		{	return loadActionsTask;
		}
		if (loadConditionsTask == null)
		{	// both null
			final TaskCompletionSource<Void> asyncResult = new TaskCompletionSource<>();
			asyncResult.setResult(null);
			return asyncResult.getTask();
		}
		else
		{	// both nonnull
			final Task<Void> finalLoadActionsTask = loadActionsTask;
			return loadConditionsTask.continueWithTask
			(	task ->
				{	if (task.getException() != null)
					{	throw task.getException();
					}
					return finalLoadActionsTask;
				}
			);
		}
	}

	/**
	 * Send Action Status to Personyze. When you show executed actions, you can report that user clicked on a click target (button), or closed that action.
	 * @param context The context of your application (usually an Activity).
     * @param actionId The action ID. Get it from PersonyzeAction object, from "id" property.
	 * @param status One of: "target", "close", "product", "article" or "error". 1. "target" means user clicked the destination button. In this case "arg" will be ignored. 2. "close" - user chosen to dismiss this action. "arg" is number of sessions not to show again. 3. "product" - user clicked on a product in a recommendation widget. "arg" is product internal ID. 4. "article" is like "product". 5. "error" - you rejected to show this action to user. "arg" is reason message (will appear in visits dashboard).
	 * @param arg See "status".
	 */
	void reportActionStatus(Context context, int actionId, String status, String arg)
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
			flush(context, false, false);
		}
	}

	private Task<PersonyzeResult> doInitialize(Context context)
	{	if (queryingResults != null) // already initialized?
		{	// ignore error in previous request
			return queryingResults.continueWith
			(	task ->
				{	if (task.isSuccessful())
					{	return task.getResult();
					}
					return personyzeResult;
				}
			);
		}
		final TaskCompletionSource<PersonyzeResult> asyncResult = new TaskCompletionSource<>();
		queryingResults = asyncResult.getTask(); // set initialized
		try
		{	if (context == null)
			{	throw new PersonyzeError("No context given");
			}
			http.setContext(context);
			storage = context.getSharedPreferences("Personyze Tracker", Context.MODE_PRIVATE);
			timeZone = TimeZone.getDefault().getRawOffset() / (60*60*1000.0);
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
			apiKeyHash = http.apiKey.hashCode();
			personyzeResult = null;
			// restore current state
			userId = storage.getInt("User ID", 0);
			if (userId == 0)
			{	while (userId == 0)
				{	userId = new Random().nextInt();
				}
				SharedPreferences.Editor editor = storage.edit();
				editor.putInt("User ID", userId);
				editor.apply();
			}
			wantNewSession = storage.getBoolean("New Session", false);
			sessionId = storage.getString("User", null);
			notiLastCheckTime = storage.getLong("Noti Last Check Time", 0);
			cacheVersion = storage.getInt("Cache Version", 0);
			if (storage.getInt("Api Key Hash", 0) != apiKeyHash)
			{	clearCache(context); // delete cached conditions and actions from (possible) different account
			}
			PersonyzeResult tr = new PersonyzeResult();
			if (tr.fromStorage(storage, context))
			{	personyzeResult = tr;
			}
			asyncResult.setResult(personyzeResult);
		}
		catch (PersonyzeError error)
		{	asyncResult.setException(error);
		}
		return asyncResult.getTask();
	}

	// MARK: public

	/**
	 * Initialize the tracker. This is required before calling any other methods. Typically you need to call this once. Second call with the same apiKey will do nothing.
	 * This method doesn't start or stop notification service. Use {@link #initialize(Context, String, boolean)} with 3rd argument true if you want to receive notifications.
	 * @param context The context of your application (usually an Activity).
	 * @param apiKey Your personal secret key, obtained in the Personyze account.
	 */
	public synchronized void initialize(Context context, String apiKey)
	{	initialize(context, apiKey, notiEnabled);
	}

	/**
	 * Initialize the tracker. This is required before calling any other methods. Typically you need to call this once. Second call with the same apiKey will do nothing.
	 * @param context The context of your application (usually an Activity).
	 * @param apiKey Your personal secret key, obtained in the Personyze account.
	 * @param notiEnabled If true, will register to receive notifications from Personyze. You need to receive system "after reboot" broadcast, and call {@link #initialize(Context, String, boolean)}. Since then notifications can arrive. If notiEnabled is false, the notification service will be deregistered.
	 */
	public synchronized void initialize(Context context, String apiKey, boolean notiEnabled)
	{	if (http.apiKey==null || !http.apiKey.equals(apiKey))
		{	queryingResults = null;
			http.apiKey = apiKey;
		}
		if (notiEnabled != this.notiEnabled)
		{	this.notiEnabled = notiEnabled;
			if (notiEnabled)
			{	// Register service
				PeriodicWorkRequest notificationWorker =
				(	new PeriodicWorkRequest.Builder
					(	PersonyzeNotificationWorker.class,
						PERIODIC_INTERVAL_MILLIS,
						TimeUnit.MILLISECONDS
					).setConstraints
					(	new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
					).build()
				);
				WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork("Personyze Notification Worker", ExistingPeriodicWorkPolicy.KEEP, notificationWorker);
			}
			else
			{	// Deregister service
				WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork("Personyze Notification Worker");
			}
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
	 * @param context The context of your application (usually an Activity).
     * @param clicked Object that action.renderOnWebView() gives you.
	 */
	public void reportActionClicked(Context context, PersonyzeAction.Clicked clicked)
	{	if (clicked != null)
		{	reportActionStatus(context, clicked.actionId, clicked.status, clicked.arg);
		}
	}

	/**
	 * What conditions are matching, and what actions are to be presented. This will send pending events to Personyze. This library remembers (stores to memory) the result, and until you call startNewSession(), you can get current result, even after object recreation.
	 * @param context The context of your application (usually an Activity).
	 */
	public Task<PersonyzeResult> getResult(Context context)
	{	return flush(context, true, false);
	}

	/**
	 * This asks Personyze to start new session for current user. For each user Personyze counts number of sessions.
	 * Call this when user closes and reopens the application. Each session lasts not more than 1.5 hours, so it will
	 * restart after this period automatically.
	 * @param context The context of your application (usually an Activity).
     */
	public void startNewSession(Context context)
	{	flush(context, false, true);
	}

	/**
	 * Call this at last. This method sends pending events to Personyze.
	 * @param context The context of your application (usually an Activity).
     */
	public void done(Context context)
	{	flush(context, false, false);
	}

	/**
	 * Normally you don't need to call this.
	 */
	public Task<Void> clearCache(Context context)
	{	queryingResults = doInitialize(context).continueWith
		(	task ->
			{	if (task.getException() != null)
				{	throw task.getException();
				}
				if (storage == null)
				{	throw new PersonyzeError("PersonyzeTracker not initialized");
				}
				Set<String> conditions = storage.getStringSet("Conditions", null);
				Set<String> actions = storage.getStringSet("Actions", null);
				SharedPreferences.Editor editor = storage.edit();
				editor.clear();
				// the following settings don't depend on cacheVersion
				editor.putInt("User ID", userId);
				editor.putInt("Api Key Hash", apiKeyHash);
				if (wantNewSession)
				{	editor.putBoolean("New Session", true);
				}
				if (sessionId != null)
				{	editor.putString("User", sessionId);
				}
				if (notiLastCheckTime != 0)
				{	editor.putLong("Noti Last Check Time", notiLastCheckTime);
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
				return task.getResult();
			}
		);
		return queryingResults.continueWith
		(	task ->
			{	if (task.getException() != null)
				{	throw task.getException();
				}
				return null;
			}
		);
	}

	public Task<Void> checkForNotification(Context context)
	{	return checkForNotification(context, true);
	}

	Task<Void> checkForNotification(Context context, boolean evenIfAlreadyCheckedRecently)
	{	if (http.apiKey == null)
		{	final TaskCompletionSource<Void> asyncResult = new TaskCompletionSource<>();
			asyncResult.setException(new PersonyzeError("Not initialized"));
			return asyncResult.getTask();
		}
		if (!notiEnabled || ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
		{	final TaskCompletionSource<Void> asyncResult = new TaskCompletionSource<>();
			asyncResult.setResult(null);
			return asyncResult.getTask();
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{	context.getSystemService(NotificationManager.class).createNotificationChannel
			(	new NotificationChannel
				(	NOTI_CHANNEL_ID,
					NOTI_CHANNEL_NAME,
					NotificationManager.IMPORTANCE_DEFAULT
				)
			);
		}
		queryingResults = doInitialize(context).continueWithTask
		(	task ->
			{	if (task.getException() != null)
				{	throw task.getException();
				}
				long now = System.currentTimeMillis();
				if (sessionId == null || notiLastCheckTime+(evenIfAlreadyCheckedRecently ? 1000 : PERIODIC_INTERVAL_MILLIS) >= now)
				{	final TaskCompletionSource<PersonyzeResult> asyncResult2 = new TaskCompletionSource<>();
					asyncResult2.setResult(personyzeResult);
					return asyncResult2.getTask();
				}
				notiLastCheckTime = now;
				SharedPreferences.Editor editor = storage.edit();
				editor.putLong("Noti Last Check Time", notiLastCheckTime);
				editor.apply();
				return http.get("current_notification/where/user_id="+userId+"&session_id="+restUriEncode(sessionId)).continueWithTask
				(	task2 ->
					{	if (task2.getException() != null)
						{	throw task2.getException();
						}
						String json = task2.getResult();
						if (json.equals("null"))
						{	final TaskCompletionSource<PersonyzeResult> asyncResult2 = new TaskCompletionSource<>();
							asyncResult2.setResult(personyzeResult);
							return asyncResult2.getTask();
						}
						PersonyzeNotification personyzeNoti = new PersonyzeNotification(json);
						return personyzeNoti.toNotification(context).continueWithTask
						(	task3 ->
							{	if (task3.getException() != null)
								{	throw task3.getException();
								}
								Notification noti = task3.getResult();
								// Clear the notification on Personyze server
								return http.delete("current_notification/where/user_id="+userId+"&session_id="+restUriEncode(sessionId)+"&message_id="+personyzeNoti.messageId).continueWith
								(	task4 ->
									{	if (task4.getException() != null)
										{	throw task4.getException();
										}
										String rowsAffected = task4.getResult();
										if (!rowsAffected.equals("0") && !rowsAffected.equals("1"))
										{	throw new PersonyzeError("Couldn't deliver the notification");
										}
										NotificationManagerCompat.from(context).notify(NOTI_CHANNEL_ID, NOTI_ID, noti);
										return personyzeResult;
									}
								);
							}
						);
					}
				);
			}
		);
		return queryingResults.continueWith(task -> null);
	}

	String restUriEncode(String value)
	{	return URLEncoder.encode(value.replace(",", "%2C")); // "," -> "%252C"
	}
}
