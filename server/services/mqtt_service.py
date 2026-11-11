"""MQTT service handler."""
import json
import threading
import time
import paho.mqtt.client as mqtt

from ..crypto import CryptoManager
from ..key_manager import Secret
from .. import config

CONNECT_TIMEOUT_SECONDS = 5
PUBLISH_TIMEOUT_SECONDS = 5
# CONNACK 4 = bad username or password, 5 = not authorised
AUTH_FAILURE_CODES = (4, 5)


class MQTTService:
    """Handles MQTT publish requests."""

    def __init__(self, crypto_manager: CryptoManager):
        """
        Initialize MQTT service handler.

        Args:
            crypto_manager: CryptoManager instance for encryption operations
        """
        self.crypto = crypto_manager

    def handle_request(self, payload: dict, secret: Secret) -> bytes:
        """
        Publish message to MQTT broker.
        
        Args:
            payload: Decrypted request payload with topic and message
            
        Returns:
            Encrypted response confirming publication
        """
        try:
            # Extract MQTT parameters
            broker_host = payload.get("broker_host", config.MQTT_BROKER_HOST)
            broker_port = payload.get("broker_port", config.MQTT_BROKER_PORT)
            topic = payload["topic"]
            message = payload["message"]
            username = payload.get("username")
            password = payload.get("password")
            qos = payload.get("qos", 0)
            retain = payload.get("retain", False)
            
            # Create MQTT client
            client = mqtt.Client()
            
            # Set credentials if provided
            if username and password:
                client.username_pw_set(username, password)
            
            # Wait for the broker's CONNACK before publishing: a bare
            # connect()+publish() reports success even when the broker
            # refuses the login and silently drops the message.
            connack = {}
            connack_received = threading.Event()

            def on_connect(_client, _userdata, _flags, rc):
                connack["rc"] = rc
                connack_received.set()

            client.on_connect = on_connect
            client.connect(broker_host, broker_port, 60)
            client.loop_start()
            try:
                if not connack_received.wait(CONNECT_TIMEOUT_SECONDS):
                    return self._broker_error(
                        504, f"Broker {broker_host}:{broker_port} did not answer CONNECT", secret
                    )
                rc = connack["rc"]
                if rc != mqtt.CONNACK_ACCEPTED:
                    status = 401 if rc in AUTH_FAILURE_CODES else 502
                    return self._broker_error(
                        status, f"Broker refused connection: {mqtt.connack_string(rc)}", secret, code=rc
                    )

                result = client.publish(topic, message, qos=qos, retain=retain)
                if result.rc == mqtt.MQTT_ERR_SUCCESS:
                    result.wait_for_publish(timeout=PUBLISH_TIMEOUT_SECONDS)
                    if not result.is_published():
                        return self._broker_error(504, "Publish not acknowledged by broker", secret)
            finally:
                client.disconnect()
                client.loop_stop()

            # Build response payload
            response_payload = {
                "status": 200 if result.rc == 0 else 500,
                "body": json.dumps({
                    "success": result.rc == 0,
                    "topic": topic,
                    "message": (
                        "Published successfully" 
                        if result.rc == 0 
                        else f"Failed with code {result.rc}"
                    )
                }),
                "timestamp": int(time.time())
            }
            
        except KeyError as e:
            response_payload = {
                "status": 400,
                "body": json.dumps({
                    "error": f"Missing required field: {str(e)}"
                }),
                "timestamp": int(time.time()),
                "source": "gateway",
            }
        except Exception as e:
            response_payload = {
                "status": 500,
                "body": json.dumps({"error": f"MQTT error: {str(e)}"}),
                "timestamp": int(time.time()),
                "source": "gateway",
            }
        
        return self.crypto.encrypt(response_payload, secret)

    def _broker_error(self, status: int, error: str, secret: Secret, code: int = None) -> bytes:
        """Broker-side failure: deliberately not tagged source=gateway (see SDK ResponseDecoder)."""
        body = {"error": error}
        if code is not None:
            body["code"] = code
        return self.crypto.encrypt({
            "status": status,
            "body": json.dumps(body),
            "timestamp": int(time.time()),
        }, secret)
