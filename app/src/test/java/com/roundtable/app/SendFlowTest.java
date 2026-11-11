package com.roundtable.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SendFlowTest {
    private SendFlow.Action page(SendFlow f,long now,int inputs,boolean draft,boolean matched,int sends,boolean enabled,boolean stable) {
        return f.next(now,true,false,false,inputs,draft,matched,sends,enabled,stable);
    }
    @Test public void voiceButtonBeforeTypingDoesNotPreventFill() {
        SendFlow f=new SendFlow(15000);
        assertEquals(SendFlow.Action.FILL,page(f,1000,1,false,false,0,false,true));
        assertEquals(SendFlow.Action.WAIT,page(f,1250,1,true,true,0,false,true));
        assertEquals(SendFlow.Action.CLICK,page(f,2000,1,true,true,1,true,true));
        assertEquals(SendFlow.Action.WAIT,page(f,2250,1,true,true,1,true,true));
    }
    @Test public void pageLoadingIsReadOnlyUntilStable() {
        SendFlow f=new SendFlow(15000);
        assertEquals(SendFlow.Action.WAIT,f.next(0,false,false,false,0,false,false,0,false,false));
        assertEquals(SendFlow.Action.WAIT,page(f,500,0,false,false,0,false,true));
        assertEquals(SendFlow.Action.WAIT,page(f,750,1,false,false,0,false,false));
        assertEquals(SendFlow.Action.FILL,page(f,1500,1,false,false,0,false,true));
    }
    @Test public void textWriteIsNeverRetried() {
        SendFlow f=new SendFlow(15000);
        assertEquals(SendFlow.Action.FILL,page(f,1000,1,false,false,0,false,true));
        assertEquals(SendFlow.Action.WAIT,page(f,2000,1,false,false,1,true,true));
        assertEquals(SendFlow.Action.ABORT,page(f,15000,1,false,false,1,true,true));
        assertEquals(SendFlow.Action.WAIT,page(f,15500,1,false,false,1,true,true));
    }
    @Test public void draftAndAmbiguousInputsAreNeverOverwritten() {
        assertEquals(SendFlow.Action.ABORT,page(new SendFlow(15000),1000,1,true,false,1,true,true));
        assertEquals(SendFlow.Action.ABORT,page(new SendFlow(15000),1000,2,false,false,1,true,true));
    }
    @Test public void loginAndOversizeTreePauseBeforeFill() {
        assertEquals(SendFlow.Action.ABORT,new SendFlow(15000).next(1000,true,true,false,1,false,false,0,false,true));
        assertEquals(SendFlow.Action.ABORT,new SendFlow(15000).next(1000,true,false,true,1,false,false,0,false,true));
    }
    @Test public void disabledOrUnverifiedSendDoesNotClick() {
        SendFlow f=new SendFlow(15000);page(f,1000,1,false,false,0,false,true);
        assertEquals(SendFlow.Action.WAIT,page(f,2000,1,true,false,1,true,true));
        assertEquals(SendFlow.Action.WAIT,page(f,2500,1,true,true,1,false,true));
        assertEquals(SendFlow.Action.ABORT,page(f,3000,1,true,true,2,true,true));
    }
    @Test public void leavingTargetCancelsAuthorization() {
        SendFlow f=new SendFlow(15000);page(f,1000,1,false,false,0,false,true);
        assertEquals(SendFlow.Action.ABORT,f.next(2000,false,false,false,0,false,false,0,false,false));
        assertEquals(SendFlow.Action.WAIT,page(f,3000,1,true,true,1,true,true));
    }
    @Test public void timeoutBeforeTargetCannotFill() {
        assertEquals(SendFlow.Action.ABORT,page(new SendFlow(15000),15000,1,false,false,1,true,true));
    }
}
