package com.roundtable.app;

final class NodeRules {
    static boolean input(boolean visible, boolean enabled, boolean password, boolean editable, boolean setTextAction) {
        return visible && enabled && !password && (editable || setTextAction);
    }
}
