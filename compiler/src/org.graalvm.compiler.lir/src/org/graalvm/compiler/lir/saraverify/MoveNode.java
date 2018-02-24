package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class MoveNode extends Node {

    private Value result;
    private Value input;
    private int resultOperandPosition;
    private int inputOperandPosition;
    private List<Node> nextNodes;

    public MoveNode(Value result, Value input, LIRInstruction instruction, int resultOperandPosition, int inputOperandPosition) {
        super(instruction);
        this.result = result;
        this.input = input;
        this.resultOperandPosition = resultOperandPosition;
        this.inputOperandPosition = inputOperandPosition;
        nextNodes = new ArrayList<>();
    }

    public Value getResult() {
        return result;
    }

    public Value getInput() {
        return input;
    }

    public int getResultOperandPosition() {
        return resultOperandPosition;
    }

    public int getInputOperandPosition() {
        return inputOperandPosition;
    }

    public List<Node> getNextNodes() {
        return nextNodes;
    }

    public void addNextNode(Node nextNode) {
        nextNodes.add(nextNode);
    }

    public void addAllNextNodes(Collection<? extends Node> nextNodesArg) {
        nextNodes.addAll(nextNodesArg);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int hashCode = 1;
        hashCode = prime * hashCode + input.hashCode();
        hashCode = prime * hashCode + inputOperandPosition;
        hashCode = prime * hashCode + System.identityHashCode(instruction);
        hashCode = prime * hashCode + result.hashCode();
        hashCode = prime * hashCode + resultOperandPosition;
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MoveNode)) {
            return false;
        }

        MoveNode moveNode = (MoveNode) obj;
        return moveNode.input.equals(this.input) && moveNode.inputOperandPosition == this.inputOperandPosition && moveNode.instruction.equals(this.instruction) &&
                        moveNode.result.equals(this.result) && moveNode.resultOperandPosition == this.resultOperandPosition ? true : false;
    }

    @Override
    public String toString() {
        return "MOVE:" + result + ":" + resultOperandPosition + "=" + input + ":" + inputOperandPosition + ":" + instruction.name();
    }

    @Override
    public String duSequenceToString() {
        String string = "";

        for (Node node : nextNodes) {
            string = string + " -> " + toString() + node.duSequenceToString() + "\n";
        }

        return string;
    }

    @Override
    public boolean isDefNode() {
        return false;
    }
}
