package com.roundtable.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class NodeRulesTest {
    @Test public void customControlMustExposeEditableOrSetText() {
        assertTrue(NodeRules.input(true,true,false,false,true));
        assertTrue(NodeRules.input(true,true,false,true,false));
        assertFalse(NodeRules.input(true,true,false,false,false));
    }
    @Test public void hiddenDisabledAndPasswordFieldsAreNeverTargets() {
        assertFalse(NodeRules.input(false,true,false,true,true));
        assertFalse(NodeRules.input(true,false,false,true,true));
        assertFalse(NodeRules.input(true,true,true,true,true));
    }
}
