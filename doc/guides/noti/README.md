## Receiving notifications

In order for your application to be able to receive notifications from Personyze, follow the following steps:

1. In your application, initialize the Personyze tracker with 3rd argument true, like this:

```java
PersonyzeTracker.inst.initialize(this, "0011223344556677889900112233445566778899", true);
```

2. On Personyze create "Email Personalization" campaign.

3. Create audience targeting rules, and match these rules in the application on your device.

4. Add action of type "Web Push Send".

5. Notifications work for offline users, so if you just matched the rules, or if you're a new user, you'll need to wait for the Personyze session to be ended.
Typically your user will start receiving notifications after 1.5 hours after first application use, when he matched the targeting rules.

6. See your user on the "QA" tab in the campaign. If the user is there, and "What executed" shows "Pending" status, this means that the notification is scheduled, and will arrive to the device.

7. The notification will show up even if the user doesn't use your application at the moment. However on many Android modifications all the installed applications by default enter "Power saving mode" list.
This doesn't let the application to receive notifications in the background. This is known issue that many Android developers are facing. The solution can be to ask the application user to select different power saving profile for the application, and forward him to the settings where he can do it. For this purpose there's a good Android library called [AutoStarter](https://github.com/judemanutd/AutoStarter?tab=readme-ov-file).
