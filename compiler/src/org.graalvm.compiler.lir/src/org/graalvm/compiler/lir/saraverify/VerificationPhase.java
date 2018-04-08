package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    private final static String DEBUG_SCOPE = "SARAVerifyVerificationPhase";

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        assert UniqueInstructionVerifier.verify(lirGenRes);

        AnalysisResult inputResult = context.contextLookup(AnalysisResult.class);

        if (inputResult == null) {
            // no input du-sequences were created by the RegisterAllocationVerificationPhase
            return;
        }

        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();
        Map<Register, DummyRegDef> inputDummyRegDefs = inputResult.getDummyRegDefs();
        Map<Constant, DummyConstDef> inputDummyConstDefs = inputResult.getDummyConstDefs();

        LIR lir = lirGenRes.getLIR();
        DebugContext debugContext = lir.getDebug();

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        AnalysisResult outputResult = duSequenceAnalysis.determineDuSequences(lirGenRes, context.registerAllocationConfig.getRegisterConfig().getAttributesMap(), inputDummyRegDefs,
                        inputDummyConstDefs, debugContext);

        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        if (!verifyDataFlow(inputDuSequences, outputDuSequences, debugContext)) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Data Flow not equal");
        }

        if (!verifyOperandCount(inputResult.getInstructionDefOperandCount(),
                        inputResult.getInstructionUseOperandCount(),
                        outputResult.getInstructionDefOperandCount(), outputResult.getInstructionUseOperandCount())) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Operand numbers not equal");
        }
    }

    public boolean verifyDataFlow(Map<Value, Set<DefNode>> inputDuSequences, Map<Value, Set<DefNode>> outputDuSequences, DebugContext debugContext) {
        List<DuSequenceWeb> inputDuSequenceWebs = createDuSequenceWebs(inputDuSequences);
        List<DuSequenceWeb> outputDuSequenceWebs = createDuSequenceWebs(outputDuSequences);

        GraphPrinter.printGraphs(inputDuSequences, inputDuSequenceWebs, outputDuSequences, outputDuSequenceWebs, debugContext);

        assert assertDuSequences(inputDuSequences, outputDuSequences, inputDuSequenceWebs, outputDuSequenceWebs, debugContext);

        logDuSequenceWebs(inputDuSequenceWebs, debugContext);
        logDuSequenceWebs(outputDuSequenceWebs, debugContext);

        if (inputDuSequenceWebs.size() != outputDuSequenceWebs.size()) {
            return false;
        }

        for (DuSequenceWeb inputDuSequenceWeb : inputDuSequenceWebs) {
            if (!outputDuSequenceWebs.stream().anyMatch(outputDuSequenceWeb -> verifyDuSequenceWebs(inputDuSequenceWeb, outputDuSequenceWeb))) {
                try (Scope s = debugContext.scope(DEBUG_SCOPE); Indent i = debugContext.indent()) {
                    debugContext.log(3, "%s", "Unmatched input du-sequence-web:\n" + inputDuSequenceWeb);
                }
                return false;
            }
        }
        return true;
    }

    private static boolean verifyDuSequenceWebs(DuSequenceWeb web1, DuSequenceWeb web2) {
        Set<DefNode> defNodes1 = web1.getDefNodes();
        Set<DefNode> defNodes2 = web2.getDefNodes();
        Set<UseNode> useNodes1 = web1.getUseNodes();
        Set<UseNode> useNodes2 = web2.getUseNodes();

        for (DefNode defNode : defNodes1) {
            if (!defNodes2.stream().anyMatch(node -> node.verify(defNode))) {
                return false;
            }
        }

        for (UseNode useNode : useNodes1) {
            if (!useNodes2.stream().anyMatch(node -> node.verify(useNode))) {
                return false;
            }
        }

        return true;
    }

    private static boolean assertDuSequences(Map<Value, Set<DefNode>> inputDuSequences, Map<Value, Set<DefNode>> outputDuSequences, List<DuSequenceWeb> inputDuSequenceWebs,
                    List<DuSequenceWeb> outputDuSequenceWebs, DebugContext debugContext) {
        List<DefNode> inputDefNodes = inputDuSequences.values().stream().flatMap(set -> set.stream()).collect(Collectors.toList());
        List<DefNode> outputDefNodes = outputDuSequences.values().stream().flatMap(set -> set.stream()).collect(Collectors.toList());

        try (Scope s = debugContext.scope(DEBUG_SCOPE)) {
            Map<Node, Integer> nodeID = new HashMap<>();
            Set<Node> visitedNodes = new HashSet<>();
            inputDefNodes.stream().forEach(node -> logDuSequence(node, nodeID, visitedNodes, debugContext));
        }

        if (inputDefNodes.size() != outputDefNodes.size()) {
            try (Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.log(3, "%s", "The number of input and output definition nodes do not match."   //
                                + "\nInput definition nodes: " + inputDefNodes.size()   //
                                + "\nOutput defininition nodes: " + outputDefNodes.size());
            }
        }

        List<DefNode> inputWebDefNodes = inputDuSequenceWebs.stream().flatMap(web -> web.getDefNodes().stream()).collect(Collectors.toList());
        List<DefNode> outputWebDefNodes = outputDuSequenceWebs.stream().flatMap(web -> web.getDefNodes().stream()).collect(Collectors.toList());
        if (inputWebDefNodes.size() != outputWebDefNodes.size()) {
            try (Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.log(3, "%s",
                                "The number of definition nodes from the input and output webs do not match." //
                                                + "\nInput webs definition nodes: " + inputWebDefNodes.size()    //
                                                + "\nOutput webs definition nodes: " + outputWebDefNodes.size());
            }
            return false;
        }

        if (inputDefNodes.size() != inputWebDefNodes.size() || outputDefNodes.size() != outputWebDefNodes.size()) {
            try (Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.log(3, "%s",
                                "The definition nodes between the du-sequences and the webs do not match." //
                                                + "\nInput du-sequences definition nodes: " + inputDefNodes.size()    //
                                                + "\nInput webs definition nodes: " + inputWebDefNodes.size()    //
                                                + "\nOutput du-sequences definition nodes: " + outputDefNodes.size()    //
                                                + "\nOutput webs definition nodes: " + outputWebDefNodes.size());
            }
            return false;
        }

        return true;
    }

    public static boolean verifyOperandCount(Map<LIRInstruction, Integer> inputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> inputInstructionUseOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionUseOperandCount) {

        for (Entry<LIRInstruction, Integer> entry : inputInstructionDefOperandCount.entrySet()) {
            Integer count = outputInstructionDefOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        for (Entry<LIRInstruction, Integer> entry : inputInstructionUseOperandCount.entrySet()) {
            Integer count = outputInstructionUseOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        return true;
    }

    public List<DuSequenceWeb> createDuSequenceWebs(Map<Value, Set<DefNode>> nodes) {
        List<DuSequenceWeb> duSequenceWebs = new ArrayList<>();
        Map<Node, DuSequenceWeb> nodeDuSequenceWeb = new HashMap<>();

        for (Set<DefNode> nodeList : nodes.values()) {
            for (DefNode node : nodeList) {
                List<Node> visitedNodes = new ArrayList<>();

                DuSequenceWeb web = createDuSequenceWeb(node, nodeDuSequenceWeb, visitedNodes, duSequenceWebs);

                if (web == null) {
                    web = new DuSequenceWeb();
                    duSequenceWebs.add(web);
                }
                web.addNodes(visitedNodes);

                final DuSequenceWeb finalWeb = web;

                Set<DefNode> defNodes = web.getDefNodes();
                defNodes.stream().forEach(defNode -> nodeDuSequenceWeb.put(defNode, finalWeb));

                Set<MoveNode> moveNodes = web.getMoveNodes();
                moveNodes.stream().forEach(moveNode -> nodeDuSequenceWeb.put(moveNode, finalWeb));

                Set<UseNode> useNodes = web.getUseNodes();
                useNodes.stream().forEach(useNode -> nodeDuSequenceWeb.put(useNode, finalWeb));
            }
        }

        return duSequenceWebs;
    }

    private DuSequenceWeb createDuSequenceWeb(Node node, Map<Node, DuSequenceWeb> nodeDuSequenceWeb, List<Node> visitedNodes, List<DuSequenceWeb> duSequenceWebs) {
        if (nodeDuSequenceWeb.containsKey(node) || visitedNodes.contains(node)) {
            return nodeDuSequenceWeb.get(node);
        }

        visitedNodes.add(node);

        return node.getNextNodes().stream()        //
                        .map(nextNode -> createDuSequenceWeb(nextNode, nodeDuSequenceWeb, visitedNodes, duSequenceWebs))           //
                        .filter(web -> web != null)                     //
                        .reduce(null, (web1, web2) -> mergeDuSequenceWebs(web1, web2, duSequenceWebs));
    }

    private static DuSequenceWeb mergeDuSequenceWebs(DuSequenceWeb web1, DuSequenceWeb web2, List<DuSequenceWeb> duSequenceWebs) {
        assert web2 != null;

        if (web1 == null || web1 == web2) {
            return web2;
        }

        duSequenceWebs.remove(web2);

        web1.addDefNodes(web2.getDefNodes());
        web1.addMoveNodes(web2.getMoveNodes());
        web1.addUseNodes(web2.getUseNodes());

        return web1;
    }

    private static void logDuSequenceWebs(List<DuSequenceWeb> duSequenceWebs, DebugContext debugContext) {
        try (Scope s = debugContext.scope(DEBUG_SCOPE); Indent i = debugContext.indent()) {
            duSequenceWebs.stream().forEach(web -> debugContext.log(4, "%s", web.toString() + "\n"));
        }
    }

    private static void logDuSequence(Node node, Map<Node, Integer> nodeID, Set<Node> visitedNodes, DebugContext debugContext) {
        if (visitedNodes.contains(node)) {
            return;
        }
        visitedNodes.add(node);

        Integer id = nodeID.get(node);
        if (id == null) {
            id = nodeID.size();
            nodeID.put(node, id);
        }

        try (Indent i = debugContext.indent()) {
            debugContext.log(4, "%s", node.toString() + " (" + id + ")");

            for (Node nextNode : node.getNextNodes()) {
                logDuSequence(nextNode, nodeID, visitedNodes, debugContext);
            }
        }
    }
}
