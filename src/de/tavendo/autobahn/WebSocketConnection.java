/******************************************************************************
 *
 *  Copyright 2011-2012 Tavendo GmbH
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package de.tavendo.autobahn;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.net.URI;

import javax.net.SocketFactory;

import android.net.SSLCertificateSocketFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import de.tavendo.autobahn.WebSocket.WebSocketConnectionObserver.WebSocketCloseNotification;
import de.tavendo.autobahn.WebSocketMessage.WebSocketCloseCode;

public class WebSocketConnection implements WebSocket {
	private static final String TAG = WebSocketConnection.class.getName();
	private static final String WS_URI_SCHEME = "ws";
	private static final String WSS_URI_SCHEME = "wss";
	private static final String WS_WRITER = "WebSocketWriter";
	private static final String WS_READER = "WebSocketReader";

	protected final Handler mHandler;

	protected WebSocketReader mReader;
	protected WebSocketWriter mWriter;
	protected HandlerThread mWriterThread;

	protected Socket mSocket;
	private SocketThread mSocketThread;

	private URI mWebSocketURI;
	private String[] mWebSocketSubprotocols;

	private WebSocket.WebSocketConnectionObserver mWebSocketConnectionObserver;

	protected WebSocketOptions mOptions;
	private boolean mPreviousConnection = false;



	public WebSocketConnection() {
		Log.d(TAG, "WebSocket connection created.");
		this.mHandler = new ThreadHandler(this);
	}



	//
	// Forward to the writer thread
	@Override
    public void sendTextMessage(final String payload) {
		mWriter.forward(new WebSocketMessage.TextMessage(payload));
	}


	@Override
    public void sendRawTextMessage(final byte[] payload) {
		mWriter.forward(new WebSocketMessage.RawTextMessage(payload));
	}


	@Override
    public void sendBinaryMessage(final byte[] payload) {
		mWriter.forward(new WebSocketMessage.BinaryMessage(payload));
	}



	@Override
    public boolean isConnected() {
		return mSocket != null && mSocket.isConnected() && !mSocket.isClosed();
	}



	private void failConnection(final WebSocketCloseNotification code, final String reason) {
		Log.d(TAG, "fail connection [code = " + code + ", reason = " + reason);
		
		final SocketThread socketThread = mSocketThread;
		if (socketThread == null) {
		    Log.d(TAG, "Already disconnected");
		    return;
		}

		if (mReader != null) {
			mReader.quit();

		} else {
			Log.d(TAG, "mReader already NULL");
		}

		if (mWriter != null) {
			mWriter.forward(new WebSocketMessage.Quit());

			try {
				mWriterThread.join();
			} catch (final InterruptedException e) {
				e.printStackTrace();
			}
		} else {
			Log.d(TAG, "mWriter already NULL");
		}

		if (mSocket != null && socketThread != null) {
			socketThread.getHandler().post(new Runnable() {

				@Override
				public void run() {
					socketThread.stopConnection();
				}
			});
		} else {
			Log.d(TAG, "mTransportChannel already NULL");
		}
		
		if (socketThread != null) {
            socketThread.getHandler().post(new Runnable() {
                
                @Override
                public void run() {
                    Looper.myLooper().quit();
                    mSocketThread = null;
                }
            });
		}

		// wait until we've posted the socket close
		if (mReader != null) {
			try {
				mReader.join();
			} catch (final InterruptedException e) {
				e.printStackTrace();
			}
		}
		onClose(code, reason);

		Log.d(TAG, "worker threads stopped");
	}



	@Override
    public void connect(final URI webSocketURI, final WebSocket.WebSocketConnectionObserver connectionObserver) throws WebSocketException {
		connect(webSocketURI, connectionObserver, new WebSocketOptions());
	}

	@Override
    public void connect(final URI webSocketURI, final WebSocket.WebSocketConnectionObserver connectionObserver, final WebSocketOptions options) throws WebSocketException {
		connect(webSocketURI, null, connectionObserver, options);
	}

	public void connect(final URI webSocketURI, final String[] subprotocols, final WebSocket.WebSocketConnectionObserver connectionObserver, final WebSocketOptions options) throws WebSocketException {
		if (isConnected()) {
			throw new WebSocketException("already connected");
		}

		if (webSocketURI == null) {
			throw new WebSocketException("WebSockets URI null.");
		} else {
			this.mWebSocketURI = webSocketURI;
			if (!mWebSocketURI.getScheme().equals(WS_URI_SCHEME) && !mWebSocketURI.getScheme().equals(WSS_URI_SCHEME)) {
				throw new WebSocketException("unsupported scheme for WebSockets URI");
			}

			this.mWebSocketSubprotocols = subprotocols;
			this.mWebSocketConnectionObserver = connectionObserver;
			this.mOptions = new WebSocketOptions(options);

			connect();
		}
	}

	@Override
    public void disconnect() {
		if (mWriter != null) {
			mWriter.forward(new WebSocketMessage.Close());
		} else {
			Log.d(TAG, "Could not send WebSocket Close .. writer already null");
		}

		this.mPreviousConnection = false;
	}

	/**
	 * Reconnect to the server with the latest options 
	 * @return true if reconnection performed
	 */
	public boolean reconnect() {
		if (!isConnected() && (mWebSocketURI != null)) {
			connect();
			return true;
		}
		return false;
	}

	private void connect() {
		mSocketThread = new SocketThread(mWebSocketURI, mOptions);

		mSocketThread.start();
		synchronized (mSocketThread) {
			try {
				mSocketThread.wait();
			} catch (final InterruptedException e) {
			}
		}
		mSocketThread.getHandler().post(new Runnable() {
			
			@Override
			public void run() {
				mSocketThread.startConnection();
			}
		});
		
		synchronized (mSocketThread) {
			try {
				mSocketThread.wait();
			} catch (final InterruptedException e) {
			}
		}

		this.mSocket = mSocketThread.getSocket();
		
		if (mSocket == null) {
			onClose(WebSocketCloseNotification.CANNOT_CONNECT, mSocketThread.getFailureMessage());
		} else if (mSocket.isConnected()) {
			try {
				createReader();
				createWriter();

				final WebSocketMessage.ClientHandshake clientHandshake = new WebSocketMessage.ClientHandshake(mWebSocketURI, null, mWebSocketSubprotocols);
				mWriter.forward(clientHandshake);
			} catch (final Exception e) {
				onClose(WebSocketCloseNotification.INTERNAL_ERROR, e.getLocalizedMessage());
			}
		} else {
			onClose(WebSocketCloseNotification.CANNOT_CONNECT, "could not connect to WebSockets server");
		}
	}

	/**
	 * Perform reconnection
	 * 
	 * @return true if reconnection was scheduled
	 */
	protected boolean scheduleReconnect() {
		/**
		 * Reconnect only if:
		 *  - connection active (connected but not disconnected)
		 *  - has previous success connections
		 *  - reconnect interval is set
		 */
		final int interval = mOptions.getReconnectInterval();
		final boolean shouldReconnect = mSocket != null && mSocket.isConnected() && mPreviousConnection && (interval > 0);
		if (shouldReconnect) {
			Log.d(TAG, "WebSocket reconnection scheduled");
			mHandler.postDelayed(new Runnable() {

				@Override
                public void run() {
					Log.d(TAG, "WebSocket reconnecting...");
					reconnect();
				}
			}, interval);
		}
		return shouldReconnect;
	}

	/**
	 * Common close handler
	 * 
	 * @param code       Close code.
	 * @param reason     Close reason (human-readable).
	 */
	private void onClose(final WebSocketCloseNotification code, final String reason) {
		boolean reconnecting = false;

		if ((code == WebSocketCloseNotification.CANNOT_CONNECT) || (code == WebSocketCloseNotification.CONNECTION_LOST)) {
			reconnecting = scheduleReconnect();
		}

		final WebSocket.WebSocketConnectionObserver webSocketObserver = mWebSocketConnectionObserver;
		if (webSocketObserver != null) {
			try {
				if (reconnecting) {
					webSocketObserver.onClose(WebSocketConnectionObserver.WebSocketCloseNotification.RECONNECT, reason);
				} else {
					webSocketObserver.onClose(code, reason);
				}
			} catch (final Exception e) {
				e.printStackTrace();
			}
		} else {
			Log.d(TAG, "WebSocketObserver null");
		}
	}




	protected void processAppMessage(final Object message) {
	}


	/**
	 * Create WebSockets background writer.
	 */
	protected void createWriter() {
	    
        mWriterThread = new HandlerThread(WS_WRITER);
        mWriterThread.start();
		mWriter = new WebSocketWriter(mWriterThread.getLooper(), mHandler, mSocket, mOptions);

		Log.d(TAG, "WebSocket writer created and started.");
	}


	/**
	 * Create WebSockets background reader.
	 */
	protected void createReader() {

		mReader = new WebSocketReader(mHandler, mSocket, mOptions, WS_READER);
		mReader.start();

		Log.d(TAG, "WebSocket reader created and started.");
	}

	private void handleMessage(final Message message) {
		final WebSocket.WebSocketConnectionObserver webSocketObserver = mWebSocketConnectionObserver;

		if (message.obj instanceof WebSocketMessage.TextMessage) {
			final WebSocketMessage.TextMessage textMessage = (WebSocketMessage.TextMessage) message.obj;

			if (webSocketObserver != null) {
				webSocketObserver.onTextMessage(textMessage.mPayload);
			} else {
				Log.d(TAG, "could not call onTextMessage() .. handler already NULL");
			}

		} else if (message.obj instanceof WebSocketMessage.RawTextMessage) {
			final WebSocketMessage.RawTextMessage rawTextMessage = (WebSocketMessage.RawTextMessage) message.obj;

			if (webSocketObserver != null) {
				webSocketObserver.onRawTextMessage(rawTextMessage.mPayload);
			} else {
				Log.d(TAG, "could not call onRawTextMessage() .. handler already NULL");
			}

		} else if (message.obj instanceof WebSocketMessage.BinaryMessage) {
			final WebSocketMessage.BinaryMessage binaryMessage = (WebSocketMessage.BinaryMessage) message.obj;

			if (webSocketObserver != null) {
				webSocketObserver.onBinaryMessage(binaryMessage.mPayload);
			} else {
				Log.d(TAG, "could not call onBinaryMessage() .. handler already NULL");
			}

		} else if (message.obj instanceof WebSocketMessage.Ping) {
			final WebSocketMessage.Ping ping = (WebSocketMessage.Ping) message.obj;
			Log.d(TAG, "WebSockets Ping received");

			final WebSocketMessage.Pong pong = new WebSocketMessage.Pong();
			pong.mPayload = ping.mPayload;
			mWriter.forward(pong);

		} else if (message.obj instanceof WebSocketMessage.Pong) {
			final WebSocketMessage.Pong pong = (WebSocketMessage.Pong) message.obj;

			Log.d(TAG, "WebSockets Pong received" + pong.mPayload);

		} else if (message.obj instanceof WebSocketMessage.Close) {
			final WebSocketMessage.Close close = (WebSocketMessage.Close) message.obj;

			Log.d(TAG, "WebSockets Close received (" + close.getCode() + " - " + close.getReason() + ")");

			mWriter.forward(new WebSocketMessage.Close(WebSocketCloseCode.NORMAL));

		} else if (message.obj instanceof WebSocketMessage.ServerHandshake) {
			final WebSocketMessage.ServerHandshake serverHandshake = (WebSocketMessage.ServerHandshake) message.obj;

			Log.d(TAG, "opening handshake received");

			if (serverHandshake.mSuccess) {
				if (webSocketObserver != null) {
					webSocketObserver.onOpen();
				} else {
					Log.d(TAG, "could not call onOpen() .. handler already NULL");
				}
				mPreviousConnection = true;
			}

		} else if (message.obj instanceof WebSocketMessage.ConnectionLost) {
			//			WebSocketMessage.ConnectionLost connectionLost = (WebSocketMessage.ConnectionLost) message.obj;
			failConnection(WebSocketCloseNotification.CONNECTION_LOST, "WebSockets connection lost");

		} else if (message.obj instanceof WebSocketMessage.ProtocolViolation) {
			//			WebSocketMessage.ProtocolViolation protocolViolation = (WebSocketMessage.ProtocolViolation) message.obj;
			failConnection(WebSocketCloseNotification.PROTOCOL_ERROR, "WebSockets protocol violation");

		} else if (message.obj instanceof WebSocketMessage.Error) {
			final WebSocketMessage.Error error = (WebSocketMessage.Error) message.obj;
			error.mException.printStackTrace();
			failConnection(WebSocketCloseNotification.INTERNAL_ERROR, "WebSockets internal error (" + error.mException.toString() + ")");

		} else if (message.obj instanceof WebSocketMessage.ServerError) {
			final WebSocketMessage.ServerError error = (WebSocketMessage.ServerError) message.obj;
			failConnection(WebSocketCloseNotification.SERVER_ERROR, "Server error " + error.mStatusCode + " (" + error.mStatusMessage + ")");

		} else {
			processAppMessage(message.obj);

		}
	}



	public static class SocketThread extends Thread {
		private static final String WS_CONNECTOR = "WebSocketConnector";

		private final URI mWebSocketURI;

		private Socket mSocket = null;
		private String mFailureMessage = null;
		
		private Handler mHandler;
		


		public SocketThread(final URI uri, final WebSocketOptions options) {
			this.setName(WS_CONNECTOR);
			
			this.mWebSocketURI = uri;
		}



		@Override
		public void run() {
			Looper.prepare();
			this.mHandler = new Handler();
			synchronized (this) {
				notifyAll();
			}
			
			Looper.loop();
			Log.d(TAG, "SocketThread exited.");
		}



		public void startConnection() {	
			try {
				final String host = mWebSocketURI.getHost();
				int port = mWebSocketURI.getPort();

				if (port == -1) {
					if (mWebSocketURI.getScheme().equals(WSS_URI_SCHEME)) {
						port = 443;
					} else {
						port = 80;
					}
				}
				
				SocketFactory factory = null;
				if (mWebSocketURI.getScheme().equalsIgnoreCase(WSS_URI_SCHEME)) {
					factory = SSLCertificateSocketFactory.getDefault();
				} else {
					factory = SocketFactory.getDefault();
				}

				// Do not replace host string with InetAddress or you lose automatic host name verification
				this.mSocket = factory.createSocket(host, port);
			} catch (final IOException e) {
				this.mFailureMessage = e.getLocalizedMessage();
			}
			
			synchronized (this) {
				notifyAll();
			}
		}
		
		public void stopConnection() {
			try {
				mSocket.close();
				this.mSocket = null;
			} catch (final IOException e) {
				this.mFailureMessage = e.getLocalizedMessage();
			}
		}

		public Handler getHandler() {
			return mHandler;
		}
		public Socket getSocket() {
			return mSocket;
		}
		public String getFailureMessage() {
			return mFailureMessage;
		}
	}



	private static class ThreadHandler extends Handler {
		private final WeakReference<WebSocketConnection> mWebSocketConnection;



		public ThreadHandler(final WebSocketConnection webSocketConnection) {
			super();

			this.mWebSocketConnection = new WeakReference<WebSocketConnection>(webSocketConnection);
		}



		@Override
		public void handleMessage(final Message message) {
			final WebSocketConnection webSocketConnection = mWebSocketConnection.get();
			if (webSocketConnection != null) {
				webSocketConnection.handleMessage(message);
			}
		}
	}
}
