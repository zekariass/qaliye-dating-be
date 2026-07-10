Base URL:
https://smsethiopia.et/api/

HTTPS required. HTTP requests are rejected.

Authentication
Include your API key in the KEY header with every request.

Keep your API key secret. Never expose it in client-side code or public repos. Generate keys in Console → API Keys.


Python (use Java for this project):

import requests
response = requests.post('https://smsethiopia.et/api/sms/send',
json={
'msisdn': '251911639555',
'text': 'Hello World'
},
headers={
'KEY': 'YOUR_API_KEY'
}
)


Send Your First SMS:
Prerequisites:

Active SMSEthiopia account
API key from Console → API Keys
Phone number for testing

Configure Headers
Header:KEY
Value:YOUR_API_KEY

Header:Content-Type
Value:application/json

Request Body:
JSON
{
"msisdn": "251911639555",
"text": "Hello World"
}

text: SMS message contentrequiredstring · max 160 chars
msisdn: Recipient phone numberrequiredstring · e.g. 251911234567

Send POST Request:
POST: https://smsethiopia.et/api/sms/send

Response
{
"status": "success",
"message": "SMS sent successfully"
}