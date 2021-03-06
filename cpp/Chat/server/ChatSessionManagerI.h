// **********************************************************************
//
// Copyright (c) 2003-2015 ZeroC, Inc. All rights reserved.
//
// This copy of Chat Demo is licensed to you under the terms described
// in the CHAT_DEMO_LICENSE file included in this distribution.
//
// **********************************************************************

#ifndef CHAT_SESSION_MANAGER_I_H
#define CHAT_SESSION_MANAGER_I_H

#include <Glacier2/Session.h>

#include <string>

#include <ChatRoom.h>

class ChatSessionManagerI : public Glacier2::SessionManager
{
public:

    ChatSessionManagerI(const ChatRoomPtr&, bool trace, const Ice::LoggerPtr& logger);
    virtual Glacier2::SessionPrx create(const std::string&,
                                        const Glacier2::SessionControlPrx&,
                                        const Ice::Current&);

private:

    const ChatRoomPtr _chatRoom;
    const bool _trace;
    const Ice::LoggerPtr _logger;
};

#endif
