# **********************************************************************
#
# Copyright (c) 2003-2015 ZeroC, Inc. All rights reserved.
#
# **********************************************************************

top_srcdir	= ../..

CLIENT		= client

TARGETS		= $(CLIENT)

OBJS		= Client.o IntLongMap.o

all:: IntLongMap.cpp IntLongMap.h

include $(top_srcdir)/make/Make.rules

CPPFLAGS	:= -I. $(CPPFLAGS)

$(CLIENT): $(OBJS)
	rm -f $@
	$(CXX) $(LDFLAGS) $(LDEXEFLAGS) $(LDEXEFLAGS) -o $@ $(OBJS) $(DB_RPATH_LINK) -lFreeze $(LIBS)

# The slice2freeze rules are structured like this to avoid issues with
# parallel make.
IntLongMap.h: IntLongMap.cpp
IntLongMap.cpp: $(SLICE2FREEZE) $(SLICEPARSERLIB)
	rm -f IntLongMap.h IntLongMap.cpp
	$(SLICE2FREEZE) -I$(slicedir) --dict IntLongMap,int,long IntLongMap

clean::
	-rm -f IntLongMap.h IntLongMap.cpp
	-rm -rf db/__* db/data/* db/logs/* db/__Freeze
	-rm -rf hotbackup hotbackup.1
