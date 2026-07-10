API Documentation
Send SMS (GET)Send SMS (POST)Send Bulk SMSSend Security CodeVerify CodeGet BalanceGet Status
Welcome, Developer! So you have the need to add SMS functionality to your system ... No worries! We got you covered! Below you will find the details of four API endpoints with example snippets for selected programming languages to simplify your integration efforts.

A Note For You:
In order to experiment with these endpoints, you should first create an account on the application dashboard, verify your account and generate a token to use for this purpose.
Make sure your URL components are properly encoded as the API expects so for anything coming as part of a URL.
Send SMS (GET)
Use this API endpoint to send SMS messages to a given phone number. This endpoint is ideal to quickly integrate SMS functionality into your systems and get started sending messages.

GET https://api.afromessage.com/api/send?from={IDENTIFIER_ID}&sender={YOUR_SENDER_NAME}&to={YOUR_RECIPIENT}&message={YOUR_MESSAGE}&callback={YOUR_CALLBACK}
The following table is a summary of each parameter used in the call.

Parameter	Type	Description	Default Value
from	string	The value of the system identifier id if you have subscribed to multiple short codes. You can find the value of this from the list of your identifiers.	Empty: The default identifier will be used if no value is given.
sender	string	The value of Sender Name to use for this message. You need to request a sender and get verified before using them. You can have multiple sender names. This will be shown as FROM value when people receive your messages.	None: The default sender name AfroMessage will be used if no sender name is provided. You can only leave this field empty if beta testing. It should be your sender name otherwise.
to	string	The value of recipient phone number you want to send your messages to.	None: Mandatory value
message	string	The SMS message you want to send. OR the template uid if you want to custom template from the system.	None: Mandatory value
template	number	Indicates the message is a template id rather than the actual message to be sent. If so, the content of the template will be used as the message to be sent.	0: Don't use template and message is not tempalte id
callback	string	The callback URL you want to receive SMS send progress. It should be a GET endpoint that we append message_id and status to it before we make the call.	Empty
Below you will find sample snippets for selected programming languages. Don't forget to substitute the values for the placeholders with the right information.

JAVA:

    /** We use OkHttp library for this purpose **/
    String baseURL = "https://api.afromessage.com/api/send";
    String token = "YOUR_TOKEN";
    
    /** init client **/
    OkHttpClient client = new OkHttpClient();

    /** Build your URL **/
    HttpUrl.Builder urlBuilder = HttpUrl.parse(baseURL).newBuilder();
    urlBuilder.addQueryParameter("to", "YOUR_RECIPIENT");
    urlBuilder.addQueryParameter("message", "YOUR_MESSAGE");
    urlBuilder.addQueryParameter("callback", "YOUR_CALLBACK");
    urlBuilder.addQueryParameter("from", "YOUR_IDENTIFIER_ID");
    urlBuilder.addQueryParameter("sender", "YOUR_SENDER_NAME");
    String url = urlBuilder.build().toString();

    /** Build your request **/
    Request request = new Request.Builder()
    .header("Authorization", "Bearer " + token)
    .url(url).build();

    /** Dispatch your request **/
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            /** General Http Error **/
        }

        @Override
        public void onResponse(Call call, final Response response) throws IOException {
            System.out.println("Server response ... " + response.body().string());
            if (!response.isSuccessful()) {
                /** Request wasn't successful ... mostly due to authorization errors **/
            } else {
                /** 200 OK Response. Need to inspect the `acknowledge` node 
                 * See if it's of `error` or `success` value and act accordingly.
                 */
            }
        }
    });

The response contains two nodes acknowledge node and response node. Inspect the value of acknowledge to see if your request has secceeded or not. Below is a sample response object.

SAMPLE SUCCESS RESULT:

    {
        "acknowledge":"error",
        "response":{
            "errors":[
                "Unable to send your message. Message content is empty..."
            ]
        }
    }


SAMPLE FAILURE RESULT:

    {
        "acknowledge":"error",
        "response":{
            "errors":[
                "Unable to send your message. Message content is empty..."
            ]
        }
    }

Send SMS (POST)
This is the same endpoint as above but using POST. Include the same parameters in the body of the request as a JSON object as opposed to putting them in the URL. This endpoint becomes useful if your message is too large and results in long URLs that are difficult to debug.

POST https://api.afromessage.com/api/send
Please refer to the above endpoint for the description of each request parameter you will need to include in the body.

Below are sample snippets for selected programming languages. As always, please don't forget to substitute the values for the placeholders with the right values.

JAVA:

    /** OkHttp library is used for the snippets **/
    String baseURL = "https://api.afromessage.com/api/send";
    String token = "YOUR_TOKEN";

    /** initialze client **/
    OkHttpClient client = new OkHttpClient();

    /** Jackson library is used for json processing.
     *  Create an object node and add values to it
     */
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("from", "YOUR_IDENTIFIER_ID");
    node.put("sender", "YOUR_SENDER_NAME");
    node.put("to", "YOUR_RECIPIENT");
    node.put("message", "YOUR_MESSAGE");
    node.put("callback", "YOUR_CALLBACK");

    /** Construct request body off of the Json Object above **/
    RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), node.toString());

    /** Construct request object with all the necessary parts **/
    Request request = new Request.Builder()
            .header("Authorization", "Bearer " + token)
            .url(baseURL)
            .post(body)
            .build();

    /** Call API **/
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            /** General Http error **/
        }

        @Override
        public void onResponse(Call call, final Response response) throws IOException {
            System.out.println("Server response ... " + response.body().string());
            if (!response.isSuccessful()) {
                /** Unsuccessful response. Mainly due to authorization errors. **/
            } else {
                /** 200 OK Response. Don't forget to inspect the `acknowledge` node 
                 * See if it's of `error` or `success` value and act accordingly.
                 */
            }
        }
    });

The response contains two nodes acknowledge node and response node. Inspect the value of acknowledge to see if your request has secceeded or not. Please refer the sample output above for the GET request. They are exactly the same.

Send Bulk SMS
This API endpoint is used to send messages to multiple recipients all at once. If you have an automated process that need to send bulk messages for selected contacts and if you don't want to use the dashboard, then this API endpoint is for you.

POST https://api.afromessage.com/api/bulk_send
To send a similar message to a list of phone numbers, the body of the request needs to be formatted as below.


{
"to":[
"PHONE_1",
"PHONE_2", ...
],
"message":"YOUR_MESSAGE",
"from":"YOUR_IDENTIFIER_ID",
"sender":"YOUR_SENDER_NAME",
"campaign":"YOUR_CAMPAIGN_NAME",
"createCallback":"YOUR_CREATE_CALLBACK For campaign action",
"statusCallback":"YOUR_STATUS_CALLBACK For message status"
}

If you want to send personalized messages to a list of phone numbers, you should modify your content and the body of the request needs to be formatted as below.


{
"to":[
{"to": "PHONE_1", "message": "MSG_1"},
{"to": "PHONE_2", "message": "MSG_2"}, ...
],
"from":"YOUR_IDENTIFIER_ID",
"sender":"YOUR_SENDER_NAME",
"campaign":"YOUR_CAMPAIGN_NAME",
"createCallback":"YOUR_CREATE_CALLBACK For campaign action",
"statusCallback":"YOUR_STATUS_CALLBACK For message status"
}

Parameter	Type	Description	Default Value
from	string	The value of the system identifier id if you have subscribed to multiple short codes. You can find the value of this from the list of your identifiers.	Empty: The default identifier will be used if no value is given.
sender	string	The value of Sender Name to use for this message. You need to request a sender and get verified before using them. You can have multiple sender names. This will be shown as FROM value when people receive your messages.	None: The default sender name AfroMessage will be used if no sender name is provided. You can only leave this field empty if beta testing. It should be your sender name otherwise.
to	array of strings	List of recipient phone numbers you want to send the message to.	None: Mandatory value
message	string	The SMS message you want to send. You can use merge fields if you want to send dynamic messages for existing contacts.	None: Mandatory value
campaign	string	The name of the campaign given for this bulk send. It will show in the list of campaigns and you can monitor the progress.	Empty: We use auto generated campaign name if this value is empty
createCallback	string	Your callback URL we will call whenever a message is queued for sending from the batch. This endpoint must be a POST endpoint that takes a JSON body.	Empty: This is optional and you can see the campaign progress from the dashboard
statusCallback	string	Your usual status callback URL you use for the other endponts. It must be a GET endpoint and we will send message status as it changes states.	Empty: You won't be notified about any status change but the data is save and can be inpsected from the dashboard.
The POST request we make to your createCallback will have the following format. We will send subsequent individual message statuses using the statusCallback URL and message_id. You can use the campaign_id to locally group your messages however you want to.


{
"campaign_id":"cd93ef88-8619-4757-85b3-552406ae51f9",
"message_id":"53d28266-b85f-456e-8e66-477ad6c3b4ba",
"message":"Message",
"to":"+251XXXXXXXXX",
"from":"Sender Name",
"status":"QUEUED"
}

Below are sample snippets for selected programming languages. As always, please don't forget to substitute the values for the placeholders with the right values.

JAVA:

    /** OkHttp library is used for the snippets **/
    String baseURL = "https://api.afromessage.com/api/bulk_send";
    String token = "YOUR_TOKEN";

    /** initialze client **/
    OkHttpClient client = new OkHttpClient();

    /** Jackson library is used for json processing.
     *  Create an object node and add values to it
     */
     ArrayNode recips = JsonNodeFactory.instance.arrayNode();
     recips.add(PHONE_1);
     recips.add(PHONE_2);
     ObjectNode node = JsonNodeFactory.instance.objectNode();
     node.put("to", recips);
     node.put("message", YOUR_MESSAGE);
     node.put("from", IDENTIFIER_ID);
     node.put("sender", YOUR_SENDER_NAME);
     node.put("campaign", YOUR_CAMPAIGN_NAME);
     node.put("createCallback", YOUR_CREATE_CALLBACK);
     node.put("statusCallback", YOUR_STATUS_CALLBACK);

    /** Construct request body off of the Json Object above **/
    RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), node.toString());

    /** Construct request object with all the necessary parts **/
    Request request = new Request.Builder()
            .header("Authorization", "Bearer " + token)
            .url(baseURL)
            .post(body)
            .build();

    /** Call API **/
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            /** General Http error **/
        }

        @Override
        public void onResponse(Call call, final Response response) throws IOException {
            System.out.println("Server response ... " + response.body().string());
            if (!response.isSuccessful()) {
                /** Unsuccessful response. Mainly due to authorization errors. **/
            } else {
                /** 200 OK Response. Don't forget to inspect the `acknowledge` node 
                 * See if it's of `error` or `success` value and act accordingly.
                 */
            }
        }
    });

The response contains two nodes acknowledge node and response node. Inspect the value of acknowledge to see if your request has secceeded or not. Please refer the sample output above for the GET request. They are exactly the same.

SAMPLE SUCCESS RESULT:

{
"acknowledge":"success",
"response":{
"message":"Bulk SMS is scheduled for send...",
"campaign_id":"cd93ef88-8619-4757-85b3-552406ae51f9"
}
}



SAMPLE FAILURE RESULT:

{
"acknowledge":"error",
"response": {
"errors":["Your account balance is low to send this number of messages...."]
}
}


Send Security Code
Whether you want to send a one time password (OTP) or put in place a two-factor-authentication in your systems, this is the endpoint you need. It's highly configurable and can send any type of security code to your customers.

GET https://api.afromessage.com/api/challenge?\
from={YOUR_IDENTIFIER_ID}&sender={YOUR_SENDER_NAME}&to={YOUR_RECIPIENT}&pr={MESSAGE_PREFIX}&ps={MESSAGE_POSTFIX}
&sb={SPACES_BEFORE}&sa={SPACES_AFTER}
&ttl={EXPIRATION_VALUE}&len={CODE_LENGTH}
&t={CODE_TYPE}&callback={YOUR_ORIGINAL_MESSAGE}
The following table is a summary of each parameter used in the call.

Parameter	Type	Description	Default Value
from	string	The value of the system identifier id if you have subscribed to multiple short codes. You can find the value of this from the list of your identifiers.	Empty: The default identifier will be used if no value is given.
sender	string	The value of Sender Name to use for this message. You need to request a sender and get verified before using them. You can have multiple sender names. This will be shown as FROM value when people receive your messages.	None: The default sender name AfroMessage will be used if no sender name is provided. You can only leave this field empty if beta testing. It should be your sender name otherwise.
to	string	The value of recipient phone number you want to send your messages to.	None: Mandatory value
len	number (integer)	The character length of the security code you want to send.	4
t	number (integer)	The type of code you want to send. This is can have three values. 0 for number only codes. 1 for alphabet only codes and 2 for alphanumeric codes.	0
ttl	number (integer)	The number of seconds for this code to stay valid after which it will be considered invalid. A value of 0 is considered never expiring.	0
callback	string	The callback URL you want to receive SMS send progress. It should be a GET endpoint that we append message_id and status to it before we make the call.	Empty
pr	string	A message prefix that you can prepend to the code you want to send.	Empty
ps	string	A message postfix that you can append right after the code you want to send.	Empty
sb	number (integer)	The number of empty spaces you want to add between generated code and message prefix	0
sa	number (integer)	The number of empty spaces you want to add between generated code and message postfix	0
The following sample snippets demonstrate how you can send codes for selected programming languages. Don't forget to substitute the values for the placeholders with the right information.

JAVA:

    /** Define your variables for the call **/
    String baseURL = "https://api.afromessage.com/api/challenge";
    String token = "YOUR_TOKEN";
    String callback = "YOUR_TOKEN";
    String from = "YOUR_IDENTIFIER_ID";
    String sender = "YOUR_SENDER_NAME";
    String to = "YOUR_RECIPIENT";
    String pre = "YOUR_MESSAGE_PREFIX";
    String post = "YOUR_MESSAGE_POSTFIX";
    int sb = SPACES_BEFORE;
    int sa = SPACES_AFTER;
    int ttl = TIME_TO_LIVE;
    int len = CODE_LENGTH;
    int type = CODE_TYPE;
    
    /** We use OkHttp library **/
    OkHttpClient client = new OkHttpClient();

    /** Construct URL with all the necessary values **/
    HttpUrl.Builder urlBuilder = HttpUrl.parse(baseURL).newBuilder();
    urlBuilder.addQueryParameter("from", from);
    urlBuilder.addQueryParameter("sender", sender);
    urlBuilder.addQueryParameter("to", to);
    urlBuilder.addQueryParameter("pr", pre);
    urlBuilder.addQueryParameter("ps", post);
    urlBuilder.addQueryParameter("callback", callback);
    urlBuilder.addQueryParameter("sb", String.valueOf(sb));
    urlBuilder.addQueryParameter("sa", String.valueOf(sa));
    urlBuilder.addQueryParameter("ttl", String.valueOf(ttl));
    urlBuilder.addQueryParameter("len", String.valueOf(len));
    urlBuilder.addQueryParameter("t", String.valueOf(type));
    
    /** Get final URL **/
    String url = urlBuilder.build().toString();

    /** Construct request **/
    Request request = new Request.Builder()
        .header("Authorization", "Bearer " + token)
        .url(url)
        .build();

    /** Dispatch call **/
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            /** General Http errors goes here **/
        }

        @Override
        public void onResponse(Call call, final Response response) throws IOException {
            System.out.println("Server response ... " + response.body().string());
            if (!response.isSuccessful()) {
                /** Unsuccessful response. Mainly due to authorization errors. **/
            } else {
                /** 200 OK Response. Don't forget to inspect the `acknowledge` node 
                 * See if it's of `error` or `success` value and act accordingly.
                 */
            }
        }
    });

The response contains two nodes acknowledge node and response node. Inspect the value of acknowledge to see if your request has secceeded or not. Below is a typical sample response object for this call.

A successful response object will contain, among other things, the code that's sent for the user, and a unique verification id that you can save for later use in the verification processs.

SAMPLE SUCCESS RESULT:

    {   
        "acknowledge":"success",
        "response":
        {
                "status":"Send is in progress...",
                "message_id":"a3ddc51c-7ffe-4eaf-8ee1-a0c6628aaa2c",
                "message":"CUT32K is your verification code",
                "to":"+251999889988",
                "code":"CUT32K",
                "verificationId":"30748c9f-487c-4c82-a48b-4080ec00996c"
            }
    }


SAMPLE FAILURE RESULT:

    {
        "acknowledge":"error",
        "response":{
            "errors":[
                "Unable to send your message. Recipient address is empty..."
            ]
        }
    }


Verify Code
What good is sending a security code if you can't easily varify it? Usaually you will have a verification system that validates codes as users submit them in reponse to a challenge. We think that's not necessary. You can easily verify security codes you sent via the /challenge endpoint easily.

GET https://api.afromessage.com/api/verify?
to={YOUR_RECIPIENT}&vc={VERIFICATION_ID}&code={CODE_TO_VERIFY}
The following table is a summary of each parameter used in the call.

Parameter	Type	Description	Default Value
to	string	The recipient phone number against which you want to verify the authenticity of codes. Either this or the verification id you received as part of the response when sending codes are mandatory for the verification process.	None: Mandatory value if vc is not given
vc	string	The verification Id you received when sending security codes using the /challenge endpoint	None: Mandatory value if to is not given
code	string	The code the user submitted and you want to verify its authenticity.	None: Mandatory
Use the following sample snippets for selected programming languages to test this endpoint. Please substitute the values for the placeholders with the right value.

JAVA:

    /** Define your variables **/
    String baseURL = "https://api.afromessage.com/api/verify";
    String token = "YOUR_TOKEN";
    /** Either verification code or phone number are required **/
    String verificationId = "VERIFICATION_ID";
    String to = "YOUR_RECIPIENT";
    String verificationCode = "CODE_TO_VERIFY";
    
    /** We use OkHttp for the snippets **/
    OkHttpClient client = new OkHttpClient();

    /** Build URL **/
    HttpUrl.Builder urlBuilder = HttpUrl.parse(baseURL).newBuilder();
    /** Either of them suffices but if you send both, we use whichever is correct **/
    urlBuilder.addQueryParameter("vc", verificationId);
    urlBuilder.addQueryParameter("to", to);
    urlBuilder.addQueryParameter("code", verificationCode);

    /** The final URL **/
    String url = urlBuilder.build().toString();

    /** Create request **/
    Request request = new Request.Builder()
        .header("Authorization", "Bearer " + token)
        .url(url)
        .build();

    /** Make call **/
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            /** General Http errors goes here **/
        }

        @Override
        public void onResponse(Call call, final Response response) throws IOException {
            System.out.println("Server response ... " + response.body().string());
            if (!response.isSuccessful()) {
                /** Unsuccessful response. Mainly due to authorization errors. **/
            } else {
                /** 200 OK Response. Don't forget to inspect the `acknowledge` node 
                 * See if it's of `error` or `success` value and act accordingly.
                 */
            }
        }
    });


The response contains two nodes acknowledge node and response node. Inspect the value of acknowledge to see if your request has secceeded or not. If verification is successful, we reply back with the entire verification object. Otherwise, we return a description of the verification error in the response.

SAMPLE SUCCESS RESULT:

    {
        "acknowledge":"success",
        "response":{
            "phone":"+251999889988",
            "code":"854467",
            "verificationId":"95c9ac5f-3d67-4d93-95d5-5083814fd7d6",
            "sentAt":"1 minute ago"
        }
    }


SAMPLE FAILURE RESULT

    {
        "acknowledge":"error",
        "response": {
            "errors":[
            "This verification code has already expired. Please re-send a new code."]
        }
    }

Get Balanace
We have a mechanism where you can set threshold values for your team balance and we notify you when your balance reaches those values. But this is not ideal all the time and you want to get in control. Not a problem. Use the /balance endpoint to get your current balance and estimated remaining sends you have for your balance.

GET https://api.afromessage.com/api/balance
No additional parameter to attach. The endpoint will return the balance information for the team where the token used to authorize the request is generated for.

Use the following sample snippets for selected programming languages to test this endpoint. Please substitute the values for the placeholders with the right value.

JAVA:

    /** Define your variables **/
    String baseURL = "https://api.afromessage.com/api/balance";
    String token = "YOUR_TOKEN";
    
    /** We use OkHttp for the snippets **/
    OkHttpClient client = new OkHttpClient();

    /** Build URL **/
    HttpUrl.Builder urlBuilder = HttpUrl.parse(baseURL).newBuilder();

    /** The final URL **/
    String url = urlBuilder.build().toString();

    /** Create request **/
    Request request = new Request.Builder()
        .header("Authorization", "Bearer " + token)
        .url(url)
        .build();

    /** Make call **/
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            /** General Http errors goes here **/
        }

        @Override
        public void onResponse(Call call, final Response response) throws IOException {
            System.out.println("Server response ... " + response.body().string());
            if (!response.isSuccessful()) {
                /** Unsuccessful response. Mainly due to authorization errors. **/
            } else {
                /** 200 OK Response. Don't forget to inspect the `acknowledge` node 
                 * See if it's of `error` or `success` value and act accordingly.
                 */
            }
        }
    });

The response contains two nodes acknowledge node and response node. Inspect the value of acknowledge to see if your request has secceeded or not. If verification is successful, we reply back with the balance information

SAMPLE SUCCESS RESULT:

    {
        "acknowledge":"success",
        "response":{
            "balance":"205.65",
            "estimatedMessages":"3577"
        }
    }


SAMPLE FAILURE RESULT:

    {
        "acknowledge":"error",
        "response": {
            "errors":[
            "... error message ..."]
        }
    }


Get Message Status
We have a conservative callback policy where we only try message status callbacks once and within a short timeout. This can be an issue when there is a network latency and the server hosting the callbacks is busy doing other things. In this situation message statuses fail to propagate even though the latest status is available on afro. The /status endpoint is here to get the latest status of a message when callbacks fail for whatever reason.

Please note: This endpoint is behind a rate limiter that allows one request every other second (30 requests per minute) and should be used with care. Your subsequent calls will be blocked for 2 minutes if you cross this limit. Inspect the response code and header for any rate limiter responses and act accordingly.
GET https://api.afromessage.com/api/status?id={MESSAGE_ID}
The following table has a brief summary of the only parameter for this request...

Parameter	Type	Description	Default Value
id	string	The message id returned from the individual send message API calls.	None: Mandatory value
As always, use the following sample snippets for selected programming languages to test this endpoint. Please substitute the values for the placeholders with the right value.

JAVA:

    /** Define your variables **/
    String baseURL = "https://api.afromessage.com/api/status";
    String token = "YOUR_TOKEN";
    /** Message id **/
    String message_id = "MESSAGE_ID";
    
    /** We use OkHttp for the snippets **/
    OkHttpClient client = new OkHttpClient();

    /** Build URL **/
    HttpUrl.Builder urlBuilder = HttpUrl.parse(baseURL).newBuilder();
    /** Parameter **/
    urlBuilder.addQueryParameter("id", messageId);

    /** The final URL **/
    String url = urlBuilder.build().toString();

    /** Create request **/
    Request request = new Request.Builder()
        .header("Authorization", "Bearer " + token)
        .url(url)
        .build();

    /** Make call **/
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            /** General Http errors goes here **/
        }

        @Override
        public void onResponse(Call call, final Response response) throws IOException {
            System.out.println("Server response ... " + response.body().string());
            if (!response.isSuccessful()) {
                /** Unsuccessful response. Mainly due to authorization errors. **/
            } else {
                /** 200 OK Response. Don't forget to inspect the `acknowledge` node 
                 * See if it's of `error` or `success` value and act accordingly.
                 */
            }
        }
    });

The response contains two nodes acknowledge node and response node. Inspect the value of response for the details of the message status. If you are trying to access messages with wrong IDs, we respond accordingly.

SAMPLE SUCCESS RESULT:

    {
        "acknowledge":"success",
        "response":{
            "messageId":"aa54b477-7eb7-4a7e-929f-7323803f6fbd",
            "cost":"0.15",
            "parts":"1",
            "status":"UNDELIV",
            "description": "Undelivered..."
        }
    }



SAMPLE FAILURE RESULT:

    {
        "acknowledge":"error",
        "response":{
            "messageId":"aa54b477-7eb7-4a7e-929f-7323803f6fbd",
            "cost":"0",
            "parts":"0",
            "status":"UNKNOWN",
            "description": "Unknown message or access is denied"
        }
    }
                                            