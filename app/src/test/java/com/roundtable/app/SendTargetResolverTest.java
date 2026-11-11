package com.roundtable.app;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SendTargetResolverTest {
    private static final String PKG="com.openai.chatgpt";
    private static final SendTargetResolver.Box ICON=new SendTargetResolver.Box(1252,1777,1342,1867);
    private static final SendTargetResolver.Box BUTTON=new SendTargetResolver.Box(1207,1732,1387,1912);
    private static class Node {
        final SendTargetResolver.Facts f=new SendTargetResolver.Facts();
        Node parent;
        final List<Node> children=new ArrayList<>();
        Node(SendTargetResolver.Box box) {f.pkg=PKG;f.visible=true;f.enabled=true;f.bounds=box;f.density=3f;f.windowId=7;}
        Node label() {f.sendLabel=true;return this;}
        Node clickable() {f.clickable=true;f.clickAction=true;return this;}
        Node add(Node child) {children.add(child);child.parent=this;return this;}
    }
    private final SendTargetResolver<Node> resolver=new SendTargetResolver<>(new SendTargetResolver.Tree<Node>() {
        public SendTargetResolver.Facts facts(Node n) {return n.f;}
        public Node parent(Node n) {return n.parent;}
        public List<Node> children(Node n) {return n.children;}
    });
    private SendTargetResolver.Result<Node> resolve(Node n) {return resolver.resolve(n,PKG);}
    @Test public void loggedNonClickableSendLabelResolvesToIsolatedButtonParent() {
        Node label=new Node(ICON).label();Node button=new Node(BUTTON).clickable().add(label);
        SendTargetResolver.Result<Node> r=resolve(label);
        assertSame(button,r.target);assertEquals(1,r.hops);
        assertFalse(label.f.clickable);assertFalse(label.f.clickAction);
    }
    @Test public void directSemanticButtonStillWorks() {
        Node button=new Node(BUTTON).clickable().label();
        assertSame(button,resolve(button).target);assertEquals(0,resolve(button).hops);
    }
    @Test public void boundedNonClickableWrapperCanLeadToGrandparent() {
        Node label=new Node(ICON).label();Node wrapper=new Node(ICON).add(label);
        Node button=new Node(BUTTON).clickable().add(wrapper);
        assertSame(button,resolve(label).target);assertEquals(2,resolve(label).hops);
    }
    @Test public void wholePageAncestorIsNeverUsed() {
        Node label=new Node(ICON).label();new Node(new SendTargetResolver.Box(0,0,1440,3200)).clickable().add(label);
        assertNull(resolve(label).target);
    }
    @Test public void composerContainingEditorIsNeverUsed() {
        Node label=new Node(ICON).label();Node editor=new Node(ICON);editor.f.inputLike=true;
        new Node(BUTTON).clickable().add(label).add(editor);
        assertNull(resolve(label).target);
    }
    @Test public void wrapperContainingAnotherActionIsNeverUsed() {
        Node label=new Node(ICON).label();Node other=new Node(ICON).clickable();
        new Node(BUTTON).clickable().add(label).add(other);
        assertNull(resolve(label).target);
    }
    @Test public void wrapperContainingAnotherSendLabelIsAmbiguous() {
        Node label=new Node(ICON).label();new Node(BUTTON).clickable().add(label).add(new Node(ICON).label());
        assertNull(resolve(label).target);
    }
    @Test public void foreignHiddenDisabledOrPasswordAncestorIsRejected() {
        for(int flag=0;flag<4;flag++) {
            Node label=new Node(ICON).label();Node parent=new Node(BUTTON).clickable().add(label);
            if(flag==0)parent.f.pkg="other.package";
            if(flag==1)parent.f.visible=false;
            if(flag==2)parent.f.enabled=false;
            if(flag==3)parent.f.password=true;
            assertNull("flag "+flag,resolve(label).target);
        }
    }
    @Test public void verificationInsideButtonScopeIsRejected() {
        Node label=new Node(ICON).label();Node verification=new Node(ICON);verification.f.blocked=true;
        new Node(BUTTON).clickable().add(label).add(verification);
        assertNull(resolve(label).target);
    }
    @Test public void cannotSkipInvalidClickableParentToOuterContainer() {
        Node label=new Node(ICON).label();Node parent=new Node(BUTTON).add(label);parent.f.clickable=true;
        new Node(BUTTON).clickable().add(parent);
        assertNull(resolve(label).target);
    }
    @Test public void excessiveDepthOrUnknownLabelCannotResolve() {
        Node label=new Node(ICON).label();Node previous=label;
        for(int i=0;i<SendTargetResolver.MAX_PARENT_HOPS;i++)previous=new Node(ICON).add(previous);
        new Node(BUTTON).clickable().add(previous);
        assertNull(resolve(label).target);
        assertNull(resolve(new Node(ICON).clickable()).target);
    }
    @Test public void missingOrOutOfBoundsGeometryIsRejected() {
        Node label=new Node(ICON).label();new Node(new SendTargetResolver.Box(0,0,180,180)).clickable().add(label);
        assertNull(resolve(label).target);
        Node empty=new Node(new SendTargetResolver.Box(1,1,1,1)).label().clickable();assertNull(resolve(empty).target);
    }
    @Test public void excessiveOrIncompleteSubtreeIsRejected() {
        Node label=new Node(ICON).label();Node parent=new Node(BUTTON).clickable().add(label);
        for(int i=0;i<65;i++)parent.add(new Node(ICON));
        assertNull(resolve(label).target);
        parent.children.clear();parent.add(label);parent.children.add(null);assertNull(resolve(label).target);
    }
    @Test public void freshResolutionRejectsChangedLabelAndChangedClickAction() {
        Node label=new Node(ICON).label();Node button=new Node(BUTTON).clickable().add(label);
        assertSame(button,resolve(label).target);
        label.f.sendLabel=false;assertNull(resolve(label).target);
        label.f.sendLabel=true;button.f.clickAction=false;assertNull(resolve(label).target);
    }
    @Test public void resolvedParentStillNeedsVerifiedInputAndSingleClickClaim() {
        Node label=new Node(ICON).label();new Node(BUTTON).clickable().add(label);
        SendFlow flow=new SendFlow(15000);
        assertEquals(SendFlow.Action.FILL,flow.next(1000,true,false,false,1,false,false,0,false,true));
        assertEquals(SendFlow.Action.WAIT,flow.next(2000,true,false,false,1,true,false,1,true,true));
        int targets=resolve(label).target==null?0:1;
        assertEquals(SendFlow.Action.CLICK,flow.next(2500,true,false,false,1,true,true,targets,true,true));
        assertEquals(SendFlow.Action.WAIT,flow.next(3000,true,false,false,1,true,true,targets,true,true));
    }
    private Node deepNode(SendTargetResolver.Box bounds) {
        Node n=new Node(bounds);n.f.pkg="com.deepseek.chat";n.f.density=3.5f;return n;
    }
    @Test public void deepSeekGlyphCanReachCompactClickableGrandparent() {
        SendTargetResolver.Box icon=new SendTargetResolver.Box(1265,1797,1321,1853);
        SendTargetResolver.Box touch=new SendTargetResolver.Box(1181,1713,1397,1923);
        assertTrue((double)touch.width()*touch.height()/(icon.width()*icon.height())>9);
        // The first two levels are from the log; the clickable grandparent is synthetic.
        Node label=deepNode(icon).label();Node wrapper=deepNode(touch).add(label);
        Node button=deepNode(touch).clickable().add(wrapper);
        SendTargetResolver.Result<Node> r=resolver.resolve(label,"com.deepseek.chat");
        assertSame(button,r.target);assertEquals(2,r.hops);
    }
    @Test public void deepSeekLabelAndSingleClickChildMayShareCompactHost() {
        SendTargetResolver.Box touch=new SendTargetResolver.Box(1181,1713,1397,1923);
        Node label=deepNode(new SendTargetResolver.Box(1265,1797,1321,1853)).label();
        Node click=deepNode(touch).clickable();deepNode(touch).add(label).add(click);
        SendTargetResolver.Result<Node> r=resolver.resolve(label,"com.deepseek.chat");
        assertSame(click,r.target);assertEquals(1,r.hops);
    }
    @Test public void compactHostWithTwoClickChildrenIsRejected() {
        Node label=new Node(ICON).label();
        new Node(BUTTON).add(label).add(new Node(BUTTON).clickable()).add(new Node(BUTTON).clickable());
        assertNull(resolve(label).target);
    }
    @Test public void clickChildMustActuallyCoverSendLabel() {
        Node label=new Node(ICON).label();
        new Node(BUTTON).add(label).add(new Node(new SendTargetResolver.Box(1207,1732,1230,1750)).clickable());
        assertNull(resolve(label).target);
    }
    @Test public void deepSeekWholeComposerRemainsOutOfScope() {
        Node label=deepNode(new SendTargetResolver.Box(1265,1797,1321,1853)).label();
        Node editor=deepNode(new SendTargetResolver.Box(99,1344,1335,1696));editor.f.inputLike=true;
        deepNode(new SendTargetResolver.Box(99,1344,1397,1923)).add(label).add(editor).add(deepNode(BUTTON).clickable());
        assertNull(resolver.resolve(label,"com.deepseek.chat").target);
    }
    @Test public void glyphSizeAndPixelDensityDoNotDetermineHostEligibility() {
        for(float density:new float[]{1f,2f,3f,3.5f,4f}) {
            int size=(int)(72*density),start=(int)(27*density),end=(int)(45*density);
            Node label=new Node(new SendTargetResolver.Box(start,start,end,end)).label();label.f.density=density;
            Node wrapper=new Node(new SendTargetResolver.Box(0,0,size,size));wrapper.f.density=density;wrapper.add(label);
            Node button=new Node(wrapper.f.bounds).clickable();button.f.density=density;button.add(wrapper);
            assertSame("density "+density,button,resolve(label).target);
        }
    }
    @Test public void absoluteDpLimitStillRejectsOversizeButton() {
        Node label=new Node(new SendTargetResolver.Box(120,120,168,168)).label();
        Node button=new Node(new SendTargetResolver.Box(0,0,288,288)).clickable().add(label);
        assertSame(button,resolve(label).target); // 96 dp at density 3
        button.f.bounds=new SendTargetResolver.Box(0,0,289,288);
        assertNull(resolve(label).target);
    }
    @Test public void invalidDensityOrUnknownWindowNeverResolves() {
        for(float density:new float[]{0f,-1f,Float.NaN,Float.POSITIVE_INFINITY}) {
            Node button=new Node(BUTTON).label().clickable();button.f.density=density;
            assertNull(resolve(button).target);
        }
        Node button=new Node(BUTTON).label().clickable();button.f.windowId=-1;assertNull(resolve(button).target);
    }
    @Test public void crossWindowOrDensityChangingWrapperIsRejected() {
        Node label=new Node(ICON).label();Node wrapper=new Node(BUTTON).clickable().add(label);
        wrapper.f.windowId=8;assertNull(resolve(label).target);
        wrapper.f.windowId=7;wrapper.f.density=4f;assertNull(resolve(label).target);
    }
    @Test public void addedActionAfterResolutionInvalidatesPreviousTarget() {
        Node label=new Node(ICON).label();Node action=new Node(BUTTON).clickable();
        Node host=new Node(BUTTON).add(label).add(action);
        assertSame(action,resolve(label).target);
        host.add(new Node(BUTTON).clickable());assertNull(resolve(label).target);
    }
}
