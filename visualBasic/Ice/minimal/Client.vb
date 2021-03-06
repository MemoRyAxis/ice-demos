' **********************************************************************
'
' Copyright (c) 2003-2014 ZeroC, Inc. All rights reserved.
'
' **********************************************************************

Imports Demo

Module MinimalC

    Public Sub Main()
        Try
            Dim communicator As Ice.Communicator = Ice.Util.initialize()
            Dim hello As HelloPrx = HelloPrxHelper.checkedCast(communicator.stringToProxy("hello:default -h localhost -p 10000"))
            hello.sayHello()
            communicator.destroy()
        Catch ex As System.Exception
            System.Console.Error.WriteLine(ex)
            System.Environment.Exit(1)
        End Try
    End Sub

End Module
