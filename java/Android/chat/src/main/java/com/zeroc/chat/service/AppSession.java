// **********************************************************************
//
// Copyright (c) 2003-2015 ZeroC, Inc. All rights reserved.
//
// **********************************************************************

package com.zeroc.chat.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import android.os.Build;
import com.zeroc.chat.R;

import android.content.res.Resources;
import android.os.Handler;

public class AppSession
{
    private Ice.Communicator _communicator;
    private Chat.ChatSessionPrx _session;
    private List<ChatRoomListener> _listeners = new LinkedList<ChatRoomListener>();
    private LinkedList<ChatEventReplay> _replay = new LinkedList<ChatEventReplay>();
    private List<String> _users = new LinkedList<String>();

    static final int MAX_MESSAGES = 200;
    private static final int INACTIVITY_TIMEOUT = 5 * 60 * 1000; // 5 Minutes

    private boolean _destroyed = false;
    private long _lastSend;
    private long _refreshTimeout;
    private Glacier2.RouterPrx _router;
    private Handler _handler;
    private String _hostname;
    private String _error;

    public AppSession(Resources resources, final Handler handler, String hostname, String username,
                      String password, boolean secure)
        throws Glacier2.CannotCreateSessionException, Glacier2.PermissionDeniedException
    {
        _handler = handler;

        Ice.InitializationData initData = new Ice.InitializationData();

        initData.dispatcher = new Ice.Dispatcher()
        {
            @Override
            public void
            dispatch(Runnable runnable, Ice.Connection connection)
            {
                handler.post(runnable);
            }
        };

        initData.properties = Ice.Util.createProperties();
        initData.properties.setProperty("Ice.RetryIntervals", "-1");
        initData.properties.setProperty("Ice.Trace.Network", "0");
        initData.properties.setProperty("Ice.Plugin.IceSSL", "IceSSL.PluginFactory");
        initData.properties.setProperty("Ice.InitPlugins", "0");
        initData.properties.setProperty("IceSSL.TruststoreType", "BKS");
        initData.properties.setProperty("IceSSL.Password", "password");

        // SDK versions < 21 only support TLSv1 with SSLEngine.
        if(Build.VERSION.SDK_INT < 21)
        {
            initData.properties.setProperty("IceSSL.Protocols", "tls1_0");
        }

        _communicator = Ice.Util.initialize(initData);
        _hostname = hostname;

        String s;
        if(secure)
        {
            java.io.InputStream certStream;
            certStream = resources.openRawResource(R.raw.client);

            IceSSL.Plugin plugin = (IceSSL.Plugin)_communicator.getPluginManager().getPlugin("IceSSL");
            plugin.setTruststoreStream(certStream);
            _communicator.getPluginManager().initializePlugins();
            s = "Glacier2/router:ssl -p 4064 -h " + hostname + " -t 10000";
        }
        else
        {
            s = "Glacier2/router:tcp -p 4502 -h " + hostname + " -t 10000";
        }

        Ice.ObjectPrx proxy = _communicator.stringToProxy(s);
        Ice.RouterPrx r = Ice.RouterPrxHelper.uncheckedCast(proxy);

        _communicator.setDefaultRouter(r);

        _router = Glacier2.RouterPrxHelper.checkedCast(r);
        Glacier2.SessionPrx glacier2session = _router.createSession(username, password);
        _session = Chat.ChatSessionPrxHelper.uncheckedCast(glacier2session);

        Ice.ObjectAdapter adapter = _communicator.createObjectAdapterWithRouter("ChatDemo.Client", _router);

        Ice.Identity callbackId = new Ice.Identity();
        callbackId.name = UUID.randomUUID().toString();
        callbackId.category = _router.getCategoryForClient();

        Ice.ObjectPrx cb = adapter.add(new ChatCallbackI(), callbackId);
        _session.setCallback(Chat.ChatRoomCallbackPrxHelper.uncheckedCast(cb));

        adapter.activate();
        _lastSend = System.currentTimeMillis();

        int acmTimeout = 0;
        try
        {
            acmTimeout = _router.getACMTimeout();
        }
        catch(Ice.OperationNotExistException ex)
        {
        }
        if(acmTimeout <= 0)
        {
            acmTimeout = (int)_router.getSessionTimeout();
        }

        Ice.Connection connection = _router.ice_getCachedConnection();
        assert(connection != null);
        connection.setCallback(new Ice.ConnectionCallback()
                                {
                                    @Override
                                    public void heartbeat(Ice.Connection con)
                                    {
                                    }

                                    @Override
                                    public void closed(Ice.Connection con)
                                    {
                                        destroyWithError("Connection closed");
                                    }
                                });

        if(acmTimeout > 0)
        {
            connection.setACM(new Ice.IntOptional(acmTimeout), null,
                              new Ice.Optional<Ice.ACMHeartbeat>(Ice.ACMHeartbeat.HeartbeatAlways));
        }

        _refreshTimeout = (acmTimeout * 1000) / 2;
    }

    synchronized public long getRefreshTimeout()
    {
        return _refreshTimeout;
    }

    synchronized public void destroy()
    {
        if(_destroyed)
        {
            return;
        }
        _destroyed = true;

        _session = null;
        new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    _router.destroySession();
                }
                catch(Exception ex)
                {
                }

                try
                {
                    _communicator.destroy();
                }
                catch(Ice.LocalException e)
                {
                }
                _communicator = null;
            }
        }).start();
    }

    synchronized public String getError()
    {
        return _error;
    }

    // This method is only called by the UI thread.
    synchronized public void send(String t)
    {
        if(_destroyed)
        {
            return;
        }

        _lastSend = System.currentTimeMillis();
        _session.begin_send(t, new Chat.Callback_ChatSession_send()
            {
                @Override
                public void exception(Ice.LocalException ex)
                {
                    destroyWithError(ex.toString());
                }

                @Override
                public void exception(Ice.UserException ex)
                {
                    destroyWithError(ex.toString());
                }

                @Override
                public void response(long ret)
                {
                }
            });
    }

    // This method is only called by the UI thread.
    public synchronized String addChatRoomListener(ChatRoomListener cb, boolean replay)
    {
        _listeners.add(cb);
        cb.init(_users);

        if(replay)
        {
            // Replay the entire state.
            for(ChatEventReplay r : _replay)
            {
                r.replay(cb);
            }
        }

        if(_error != null)
        {
            cb.error();
        }

        return _hostname;
    }

    // This method is only called by the UI thread.
    synchronized public void removeChatRoomListener(ChatRoomListener cb)
    {
        _listeners.remove(cb);
    }

    // Returns false if the session has been destroyed, true otherwise.
    synchronized public boolean refresh()
    {
        if(_destroyed)
        {
            return false;
        }

        // If the user has not sent a message in the INACTIVITY_TIMEOUT interval
        // then drop the session.
        if(System.currentTimeMillis() - _lastSend > INACTIVITY_TIMEOUT)
        {
            destroy();
            _error = "The session was dropped due to inactivity.";

            final List<ChatRoomListener> copy = new ArrayList<ChatRoomListener>(_listeners);
            _handler.post(new Runnable()
            {
                public void run()
                {
                    for(ChatRoomListener listener : copy)
                    {
                        listener.inactivity();
                    }
                }
            });
            return false;
        }

        return true;
    }

    private interface ChatEventReplay
    {
        public void replay(ChatRoomListener cb);
    }

    private class ChatCallbackI extends Chat._ChatRoomCallbackDisp
    {
        public void init(String[] users, Ice.Current current)
        {
            List<ChatRoomListener> copy;
            final List<String> u = Arrays.asList(users);

            synchronized(this)
            {
                _users.clear();
                _users.addAll(u);

                // No replay event for init.
                copy = new ArrayList<ChatRoomListener>(_listeners);
            }

            for(ChatRoomListener listener : copy)
            {
                listener.init(u);
            }
        }

        public void join(final long timestamp, final String name, Ice.Current current)
        {
            List<ChatRoomListener> copy;

            synchronized(this)
            {
                _replay.add(new ChatEventReplay()
                {
                    public void replay(ChatRoomListener cb)
                    {
                        cb.join(timestamp, name);
                    }
                });
                if(_replay.size() > MAX_MESSAGES)
                {
                    _replay.removeFirst();
                }

                _users.add(name);

                copy = new ArrayList<ChatRoomListener>(_listeners);
            }

            for(ChatRoomListener listener : copy)
            {
                listener.join(timestamp, name);
            }
        }

        public void leave(final long timestamp, final String name, Ice.Current current)
        {
            List<ChatRoomListener> copy;

            synchronized(this)
            {
                _replay.add(new ChatEventReplay()
                {
                    public void replay(ChatRoomListener cb)
                    {
                        cb.leave(timestamp, name);
                    }
                });
                if(_replay.size() > MAX_MESSAGES)
                {
                    _replay.removeFirst();
                }

                _users.remove(name);

                copy = new ArrayList<ChatRoomListener>(_listeners);
            }

            for(ChatRoomListener listener : copy)
            {
                listener.leave(timestamp, name);
            }
        }

        public void send(final long timestamp, final String name, final String message, Ice.Current current)
        {
            List<ChatRoomListener> copy;

            synchronized(this)
            {
                _replay.add(new ChatEventReplay()
                {
                    public void replay(ChatRoomListener cb)
                    {
                        cb.send(timestamp, name, message);
                    }
                });
                if(_replay.size() > MAX_MESSAGES)
                {
                    _replay.removeFirst();
                }

                copy = new ArrayList<ChatRoomListener>(_listeners);
            }

            for(ChatRoomListener listener : copy)
            {
                listener.send(timestamp, name, message);
            }
        }

        public static final long serialVersionUID = 1;
    }

    //
    // Any exception destroys the session.
    //
    // Should only be called from the main thread.
    //
    private void destroyWithError(final String msg)
    {
        List<ChatRoomListener> copy;

        synchronized(this)
        {
            destroy();
            _error = msg;

            copy = new ArrayList<ChatRoomListener>(_listeners);
        }

        for(ChatRoomListener listener : copy)
        {
            listener.error();
        }
    }
}
