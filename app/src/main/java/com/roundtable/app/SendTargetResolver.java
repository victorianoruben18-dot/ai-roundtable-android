package com.roundtable.app;

import java.util.ArrayList;
import java.util.List;

/** Resolves a semantic send label inside an isolated, compact button host. Never clicks. */
final class SendTargetResolver<N> {
    static final int MAX_PARENT_HOPS=3;
    private static final int MAX_SUBTREE_NODES=64;
    private static final int MAX_SUBTREE_DEPTH=10;
    static final float MAX_BUTTON_DP=96f;

    interface Tree<N> {
        Facts facts(N node);
        N parent(N node);
        List<N> children(N node);
    }
    static final class Box {
        final int left,top,right,bottom;
        Box(int left,int top,int right,int bottom) {this.left=left;this.top=top;this.right=right;this.bottom=bottom;}
        long width() {return (long)right-left;}
        long height() {return (long)bottom-top;}
        boolean valid() {return width()>0 && height()>0;}
        boolean smallWrapperFor(Box label,float density) {
            if(label==null || !valid() || !label.valid())return false;
            if(left>label.left || top>label.top || right<label.right || bottom<label.bottom)return false;
            // A small glyph may have a much larger touch target. Bound the host by Android
            // layout size, not by the glyph's area. Bounds never synthesize a coordinate tap.
            if(Float.isNaN(density) || Float.isInfinite(density) || density<=0)return false;
            return width()/density<=MAX_BUTTON_DP && height()/density<=MAX_BUTTON_DP;
        }
    }
    static final class Facts {
        String pkg="";
        boolean visible,enabled,inputLike,password,clickable,clickAction,sendLabel,blocked;
        Box bounds;
        float density;
        int windowId;
    }
    static final class Result<N> {
        final N target;
        final int hops;
        final String reason;
        final List<N> chain;
        Result(N target,int hops,String reason,List<N> chain) {
            this.target=target;this.hops=hops;this.reason=reason;this.chain=chain;
        }
    }
    private final Tree<N> tree;
    SendTargetResolver(Tree<N> tree) {this.tree=tree;}

    Result<N> resolve(N label,String pkg) {
        List<N> chain=new ArrayList<>();
        if(label==null || pkg==null || pkg.isEmpty())return reject(0,"缺少标签或目标应用",chain);
        Facts source=tree.facts(label);
        if(!safe(source,pkg) || source.windowId<0 || !source.sendLabel || source.bounds==null || !source.bounds.valid())
            return reject(0,"发送标签不可用或已变化",chain);
        N node=label;
        for(int hops=0;hops<=MAX_PARENT_HOPS && node!=null;hops++) {
            chain.add(node);
            Facts f=tree.facts(node);
            if(!safe(f,pkg) || f.windowId!=source.windowId || Float.compare(f.density,source.density)!=0)
                return reject(hops,"父级不可用、窗口/来源或显示比例发生变化",chain);
            if(f.bounds==null || !f.bounds.smallWrapperFor(source.bounds,source.density))
                return reject(hops,"父级超出96dp按钮范围或未包含标签",chain);
            Check check=new Check();
            inspect(node,pkg,source.windowId,check,0);
            if(check.problem!=null)return reject(hops,check.problem,chain);
            if(check.labels!=1)return reject(hops,"按钮范围内发送标签不唯一",chain);
            if(check.actions.size()>1)return reject(hops,"按钮范围内含多个可操作控件",chain);
            if(check.actions.size()==1) {
                N action=check.actions.get(0);
                Facts target=tree.facts(action);
                if(!safe(target,pkg) || target.windowId!=source.windowId || Float.compare(target.density,source.density)!=0
                        || !target.clickable || !target.clickAction || target.bounds==null
                        || !target.bounds.smallWrapperFor(source.bounds,source.density))
                    return reject(hops,"唯一点击控件未覆盖标签、超出范围或已变化",chain);
                String reason=action.equals(label)?"直接发送控件":action.equals(node)?"发送标签的独立可点击父级":"独立按钮容器内唯一点击控件";
                return new Result<>(action,hops,reason,chain);
            }
            node=tree.parent(node);
        }
        return reject(chain.size()-1,"限定父级层数内未找到独立发送控件",chain);
    }
    private boolean safe(Facts f,String pkg) {
        return f!=null && pkg.equals(f.pkg) && f.visible && f.enabled && !f.inputLike && !f.password && !f.blocked;
    }
    private final class Check {
        int nodes,labels;
        final List<N> actions=new ArrayList<>();
        String problem;
    }
    private void inspect(N node,String pkg,int windowId,Check check,int depth) {
        if(check.problem!=null)return;
        if(node==null || depth>MAX_SUBTREE_DEPTH || ++check.nodes>MAX_SUBTREE_NODES) {check.problem="按钮子树不完整或超出检查上限";return;}
        Facts f=tree.facts(node);
        if(f==null || !pkg.equals(f.pkg) || f.windowId!=windowId || f.inputLike || f.password || f.blocked) {check.problem="按钮子树含输入、验证或其他来源控件";return;}
        if(f.clickable || f.clickAction) {
            if(!safe(f,pkg) || !f.clickable || !f.clickAction) {check.problem="控件不可用或点击标记与 CLICK 动作不一致";return;}
            check.actions.add(node);
        }
        if(f.visible && f.sendLabel)check.labels++;
        List<N> children=tree.children(node);
        if(children==null) {check.problem="无法确认按钮子树";return;}
        for(N child:children)inspect(child,pkg,windowId,check,depth+1);
    }
    private Result<N> reject(int hops,String reason,List<N> chain) {return new Result<>(null,hops,reason,chain);}
}
