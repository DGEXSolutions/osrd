package fr.sncf.osrd.utils.graph;

import java.util.ArrayList;

public abstract class DirNGraph<
        EdgeT extends DirNEdge,
        NodeT extends Node
        > extends DirGraph<EdgeT> implements INodeGraph<NodeT> {

    private final ArrayList<NodeT> nodes = new ArrayList<>();

    @Override
    public NodeT getNode(int i) {
        return nodes.get(i);
    }

    @Override
    public int getNodeCount() {
        return nodes.size();
    }

    @Override
    public Iterable<NodeT> iterNodes() {
        return nodes;
    }

    @Override
    public void registerNode(NodeT node) {
        if (node.index == nodes.size())
            nodes.add(node);
        else
            nodes.set(node.index, node);
    }

    @Override
    public int nextNodeIndex() {
        return nodes.size();
    }

    @Override
    public void resizeNodes(int count) {
        nodes.ensureCapacity(count);
        while (nodes.size() < count)
            nodes.add(null);
    }
}
