# class PersonyzeCondition (Personyze Android SDK reference)

This object represents a matching condition (aka campaign). You get condition objects from call to [PersonyzeTracker.inst.getResult()](../PersonyzeTracker/README.md#personyzetrackerinstgetresult)

### condition.getId()

```java
int getId()
```

Returns the condition ID that distinguishes one condition from another. This is Personyze internal property.

### condition.getName()

```java
String getName()
```

Returns the campaign name - how you called it in the Personyze panel. The result is never null.
