// **********************************************************************
//
// Copyright (c) 2003-2015 ZeroC, Inc. All rights reserved.
//
// **********************************************************************

#import <LoginController.h>
#import <ChatController.h>

#import <Chat.h>
#import <Router.h>

#import <objc/Ice.h>
#import <objc/Glacier2.h>

NSString* const serverKey = @"hostnameKey";
NSString* const usernameKey = @"usernameKey";
NSString* const passwordKey = @"passwordKey";

@implementation LoginController

// Initialize the app defaults.
+(void)initialize
{
    // Initialize the application defaults.
    NSDictionary* appDefaults = [NSDictionary dictionaryWithObjectsAndKeys:
                                 @"demo.zeroc.com", serverKey,
                                 @"", usernameKey,
                                 @"", passwordKey,
                                 nil];
    
    [[NSUserDefaults standardUserDefaults] registerDefaults:appDefaults];
}

-(id)init
{
    return [super initWithWindowNibName:@"LoginView"];
}

-(void)awakeFromNib
{
    // Restore the field values from the app defaults.
    NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
	
    chatServerField.stringValue = [defaults stringForKey:serverKey];
    usernameField.stringValue = [defaults stringForKey:usernameKey];
    passwordField.stringValue = [defaults stringForKey:passwordKey];
}

#pragma mark Login callbacks

-(ChatController*)doGlacier2Login:(id)proxy
{
    id<GLACIER2RouterPrx> router = [GLACIER2RouterPrx checkedCast:proxy];
    id<GLACIER2SessionPrx> glacier2session = [router createSession:usernameField.stringValue
														  password:passwordField.stringValue];
    id<ChatChatSessionPrx> session = [ChatChatSessionPrx uncheckedCast:glacier2session];
    
    int sessionTimeout = [router getSessionTimeout];
    NSString* category = [router getCategoryForClient];
    
    return [[ChatController alloc] initWithCommunicator:[proxy ice_getCommunicator]
												session:session
										 sessionTimeout:sessionTimeout
												 router:router
											   category:category];
}

#pragma mark Login

-(void)doLogin:(id)sender
{
    NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
    [defaults setObject:usernameField.stringValue forKey:usernameKey];
    [defaults setObject:passwordField.stringValue forKey:passwordKey];
    
    ICEInitializationData* initData = [ICEInitializationData initializationData];
    initData.properties = [ICEUtil createProperties ];
    [initData.properties setProperty:@"Ice.ACM.Client.Timeout" value:@"0"];
    [initData.properties setProperty:@"Ice.RetryIntervals" value:@"-1"];

    
    initData.dispatcher = ^(id<ICEDispatcherCall> call, id<ICEConnection> con)
    {
        dispatch_sync(dispatch_get_main_queue(), ^ { [call run]; });
    };
	
    [initData.properties setProperty:@"IceSSL.CheckCertName" value:@"0"];
    [initData.properties setProperty:@"IceSSL.TrustOnly.Client" value:@"CN=\"127.0.0.1\""];


    [initData.properties setProperty:@"IceSSL.CertAuthFile" value:@"cacert.pem"];

    [initData.properties setProperty:@"IceSSL.DefaultDir" value:[[NSBundle mainBundle] resourcePath]];
	
    NSAssert(communicator == nil, @"communicator == nil");
    communicator = [ICEUtil createCommunicator:initData];
	
    SEL loginSelector;
    id<ICEObjectPrx> proxy;
    @try
    {
        NSString *hostname = [chatServerField.stringValue stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
        NSString* s = [NSString stringWithFormat:@"Glacier2/router:ssl -p %d -h %@ -t 10000", 4064, hostname];
        proxy = [communicator stringToProxy:s];
        [communicator setDefaultRouter:[ICERouterPrx uncheckedCast:proxy]];
        loginSelector = @selector(doGlacier2Login:);
    }
    @catch(ICEEndpointParseException* ex)
    {
        [communicator destroy];
		communicator = nil;
		
		NSRunAlertPanel(@"Error", @"%@", @"OK", nil, nil, [ex description]);
		return;
    }
    
    [NSApp beginSheet:connectingSheet 
       modalForWindow:self.window
        modalDelegate:nil 
       didEndSelector:NULL 
          contextInfo:NULL];
    [progress startAnimation:self];
    
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^ {
		NSString* msg;
		@try
		{
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
			ChatController* chatController = [self performSelector:loginSelector withObject:proxy];
#pragma clang diagnostic pop
			dispatch_async(dispatch_get_main_queue(), ^ {
				// Hide the connecting sheet.
				[NSApp endSheet:connectingSheet];
				[connectingSheet orderOut:self.window];
				[progress stopAnimation:self];
				
				// The communicator is now owned by the ChatController.
				communicator = nil;
				
				// Close the connecting window, show the main window.
				[self.window close];
				[chatController showWindow:self];
			});
			return;
		}
		@catch(GLACIER2CannotCreateSessionException* ex)
		{
			msg = [NSString stringWithFormat:@"Session creation failed: %@", ex.reason_];
		}
		@catch(GLACIER2PermissionDeniedException* ex)
		{
			msg = [NSString stringWithFormat:@"Login failed: %@", ex.reason_];
		}
		@catch(ICEException* ex)
		{
			msg = [ex description];
		}
		@catch(NSException *ex)
		{
			msg = [ex reason];
		}
		
		dispatch_async(dispatch_get_main_queue(), ^ {
			// Hide the connecting sheet.
			[NSApp endSheet:connectingSheet]; 
			[connectingSheet orderOut:self.window];
			[progress stopAnimation:self];
			
			[communicator destroy];
			communicator = nil;
			
			NSRunAlertPanel(@"Error", @"%@", @"OK", nil, nil, msg);
		});
    });    
}

-(void)showAdvancedSheet:(id)sender
{
    [NSApp beginSheet:advancedSheet 
       modalForWindow:self.window 
        modalDelegate:nil 
       didEndSelector:NULL 
          contextInfo:NULL];
}

-(void)closeAdvancedSheet:(id)sender
{
    NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
    
    [defaults setObject:chatServerField.stringValue forKey:serverKey];
	
    [NSApp endSheet:advancedSheet]; 
    [advancedSheet orderOut:sender]; 
}

@end
