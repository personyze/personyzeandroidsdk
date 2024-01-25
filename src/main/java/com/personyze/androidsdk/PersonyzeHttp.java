package com.personyze.androidsdk;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;
import android.widget.ImageView;

import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Response;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.ImageRequest;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

public class PersonyzeHttp
{	private RequestQueue requestQueue;
	private String httpAuth;
	private String apiKeyInUse;
	public String apiKey;

	public void setContext(Context context)
	{	requestQueue = Volley.newRequestQueue(context);
	}

	public Task<String> get(String path)
	{	return fetch(path, null, false);
	}

	public Task<String> post(String path, final String postData)
	{	return fetch(path, postData, false);
	}

	public Task<String> delete(String path)
	{	return fetch(path, null, true);
	}

	private Task<String> fetch(String path, final String postData, boolean isDelete)
	{	final TaskCompletionSource<String> asyncResult = new TaskCompletionSource<>();
		if (apiKey != null)
		{	if (!apiKey.equals(apiKeyInUse))
			{	if (apiKey.length() != 40)
				{	asyncResult.setException(new PersonyzeError("API Key must be 40 characters", PersonyzeError.Type.MALFORMED_API_KEY));
					return asyncResult.getTask();
				}
				apiKeyInUse = apiKey;
				String creds = "api:" + apiKeyInUse;
				httpAuth = "Basic " + Base64.encodeToString(creds.getBytes(), Base64.DEFAULT);
			}
			requestQueue.add
			(	new StringRequest
				(	isDelete ? Request.Method.DELETE : postData==null ? Request.Method.GET : Request.Method.POST,
					PersonyzeTracker.GATEWAY_URL+path,
					response ->
					{	if (response!=null && response.length()>0)
						{	asyncResult.setResult(response);
						}
						else
						{	asyncResult.setException(new PersonyzeError("Empty response from server"));
						}
					},
					error->
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
						asyncResult.setException(new PersonyzeError(message, type));
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
						params.put("User-Agent", PersonyzeTracker.USER_AGENT);
						return params;
					}
				}
			);
		}
		else
		{	asyncResult.setException(new PersonyzeError("PersonyzeTracker not initialized"));
		}
		return asyncResult.getTask();
	}

	public Task<Bitmap> getBitmap(final String href)
	{	final TaskCompletionSource<Bitmap> asyncResult = new TaskCompletionSource<>();
		requestQueue.add
		(	new ImageRequest
			(	href,
				asyncResult::setResult,
				0,
				0,
				ImageView.ScaleType.CENTER,
				null,
				error->
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
					asyncResult.setException(new PersonyzeError(message, type));
				}
			)
			{	@Override protected Response<Bitmap> parseNetworkResponse(NetworkResponse response)
				{	if (response.headers != null)
					{	String type = response.headers.get("Content-Type");
						if (type!=null && type.equals("image/svg+xml"))
						{	try
							{	SVG svg = SVG.getFromString(new String(response.data));
								int h = (int)svg.getDocumentHeight();
								int w = (int)svg.getDocumentWidth();
								Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_4444); // transparent
								Canvas canvas = new Canvas(image);
								svg.renderToCanvas(canvas);
								return Response.success(image, HttpHeaderParser.parseCacheHeaders(response));
							}
							catch (SVGParseException error)
							{	return Response.error(new ParseError(error));
							}
						}
					}
					return super.parseNetworkResponse(response);
				}
			}
		);
		return asyncResult.getTask();
	}
}
