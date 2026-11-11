package com.roundtable.app;

import java.util.ArrayList;
import java.util.List;

/** Resolves a semantic send label to a small, isolated, actionable ancestor. Never clicks. */
final class SendTargetResolver<N> {
    static final int MAX_PARENT_HOPS=3;
    private static final int MAX_SUBTREE_NODES=64;
    private static final int MAX_SUBTREE_DEPTH=10;

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
        boolean smallWrapperFor(Box label) {
            if(label==null || !valid() || !label.valid())return false;
            if(left>label.left || top>label.top || right<label.right || bottom<label.bottom)return false;
            // Geometry restricts the ancestor scope; it is never used to synthesize a tap.
            double wr=(double)width()/label.width(), hr=(double)height()/label.height();
            return wr<=4 && hr<=4 && wr*hr<=9;
        }
    }
    static final class Facts {
        String pkg="";
        boolean visible,enabled,inputLike,password,clickable,clickAction,sendLabel,blocked;
        Box bounds;
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
        if(!safe(source,pkg) || !source.sendLabel || source.bounds==null || !source.bounds.valid())
            return reject(0,"发送标签不可用或已变化",chain);
        N node=label;
        for(int hops=0;hops<=MAX_PARENT_HOPS && node!=null;hops++) {
            chain.add(node);
            Facts f=tree.facts(node);
            if(!safe(f,pkg))return reject(hops,"父级不可用、含输入能力或来源不符",chain);
            if(f.bounds==null || !f.bounds.smallWrapperFor(source.bounds))return reject(hops,"父级范围过大或未包含标签",chain);
            if(f.clickable || f.clickAction) {
                if(!f.clickable || !f.clickAction)return reject(hops,"点击标记与 CLICK 动作不一致",chain);
                Check check=new Check();
                inspect(node,node,pkg,check,0);
                if(check.problem!=null)return reject(hops,check.problem,chain);
                if(check.labels!=1)return reject(hops,"按钮范围内发送标签不唯一",chain);
                return new Result<>(node,hops,hops==0?"直接发送控件":"发送标签的独立可点击父级",chain);
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
        String problem;
    }
    private void inspect(N node,N target,String pkg,Check check,int depth) {
        if(check.problem!=null)return;
        if(node==null || depth>MAX_SUBTREE_DEPTH || ++check.nodes>MAX_SUBTREE_NODES) {check.problem="按钮子树不完整或超出检查上限";return;}
        Facts f=tree.facts(node);
        if(f==null || !pkg.equals(f.pkg) || f.inputLike || f.password || f.blocked) {check.problem="按钮子树含输入、验证或其他来源控件";return;}
        if(!target.equals(node) && (f.clickable || f.clickAction)) {check.problem="父级还包含其他可操作控件";return;}
        if(f.visible && f.sendLabel)check.labels++;
        List<N> children=tree.children(node);
        if(children==null) {check.problem="无法确认按钮子树";return;}
        for(N child:children)inspect(child,target,pkg,check,depth+1);
    }
    private Result<N> reject(int hops,String reason,List<N> chain) {return new Result<>(null,hops,reason,chain);}
}
