package com.personyze.androidsdk;

public class PersonyzeError extends Exception
{	enum Type
	{	OTHER, MALFORMED_API_KEY, REQUEST_TOO_BIG, HTTP_401, HTTP_500, HTTP_503
	}

	private Type type = Type.OTHER;

	public PersonyzeError(String message)
	{	super(message==null ? "General error" : message);
	}

	public PersonyzeError(String message, Type type)
	{	super(message==null ? "General error" : message);
		this.type = type;
	}

	public Type getType()
	{	return type;
	}
}
