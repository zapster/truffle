package org.graalvm.compiler.lir.saraverify;

import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

public class DuSequenceAnalysis {

    public static class DummyDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyDef> TYPE = LIRInstructionClass.create(DummyDef.class);
        @Def({REG}) protected AllocatableValue value;

        public DummyDef(AllocatableValue value) {
            super(TYPE);
            this.value = value;
        }

        public void setValue(AllocatableValue value) {
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    private int operandDefPosition;
    private int operandUsePosition;

    private ArrayList<DuPair> duPairs;
    private ArrayList<DuSequence> duSequences;
    private ArrayList<DuSequenceWeb> duSequenceWebs;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    private SARAVerifyValueComparator saraVerifyValueComparator = new SARAVerifyValueComparator();

    public static final String ERROR_MSG_PREFIX = "SARA verify error: ";

    private static void logInstructions(LIR lir) {
        DebugContext debug = lir.getDebug();

        try (Indent i1 = debug.indent()) {
            for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
                debug.log(3, "Visiting Block: " + block.getId());
                try (Indent i2 = debug.indent()) {
                    for (LIRInstruction instr : lir.getLIRforBlock(block)) {
                        debug.log(3, instr.toString());
                    }
                }
            }
        }
    }

    public AnalysisResult determineDuSequenceWebs(LIRGenerationResult lirGenRes, RegisterAttributes[] registerAttributes, Map<Register, DummyDef> dummyDefs) {
        LIR lir = lirGenRes.getLIR();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();
        BitSet blockQueue = new BitSet(blocks.length);

        if (!(lir.getControlFlowGraph().getLoops().isEmpty())) {
            // control flow contains one or more loops
            return null;
        }

        initializeCollections();
        logInstructions(lir);

        // start with leaf blocks
        for (AbstractBlockBase<?> block : blocks) {
            if (block.getSuccessorCount() == 0) {
                blockQueue.set(block.getId());
            }
        }

        BitSet visitedBlocks = new BitSet(blocks.length);
        HashMap<AbstractBlockBase<?>, Map<Value, List<ValUsage>>> blockValUseInstructions = new HashMap<>();

        while (!blockQueue.isEmpty()) {
            // get any block, whose successors have already been visited, remove it from the queue and add its
            // predecessors to the queue
            int blockId = blockQueue.stream().filter(id -> Arrays.asList(blocks[id].getSuccessors()).stream().allMatch(b -> visitedBlocks.get(b.getId()))).findFirst().getAsInt();
            blockQueue.clear(blockId);
            visitedBlocks.set(blockId);

            AbstractBlockBase<?> block = blocks[blockId];
            for (AbstractBlockBase<?> predecessor : block.getPredecessors()) {
                blockQueue.set(predecessor.getId());
            }

            // get instructions of block
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

            Map<Value, List<ValUsage>> valUseInstructions = mergeMaps(blockValUseInstructions, block.getSuccessors(), saraVerifyValueComparator);
            blockValUseInstructions.put(block, valUseInstructions);

            determineDuSequenceWebs(instructions, valUseInstructions);
        }
        assert visitedBlocks.length() == visitedBlocks.cardinality() && visitedBlocks.cardinality() == blocks.length && visitedBlocks.stream().allMatch(id -> id < blocks.length);

        // analysis of dummy instructions
        analyseUndefinedValues(blockValUseInstructions.get(blocks[0]), registerAttributes, dummyDefs);

        return new AnalysisResult(duPairs, duSequences, duSequenceWebs, instructionDefOperandCount, instructionUseOperandCount, dummyDefs);
    }

    public AnalysisResult determineDuSequenceWebs(List<LIRInstruction> instructions, RegisterAttributes[] registerAttributes, Map<Register, DummyDef> dummyDefs) {
        initializeCollections();

        // analysis of given instructions
        Map<Value, List<ValUsage>> valUseInstructions = new TreeMap<>(new SARAVerifyValueComparator());
        determineDuSequenceWebs(instructions, valUseInstructions);

        // analysis of dummy instructions
        analyseUndefinedValues(valUseInstructions, registerAttributes, dummyDefs);

        return new AnalysisResult(duPairs, duSequences, duSequenceWebs, instructionDefOperandCount, instructionUseOperandCount, dummyDefs);
    }

    private void determineDuSequenceWebs(List<LIRInstruction> instructions, Map<Value, List<ValUsage>> valUseInstructions) {
        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(valUseInstructions);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(valUseInstructions);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            operandDefPosition = 0;
            operandUsePosition = 0;

            visitValues(inst, defConsumer, useConsumer, useConsumer);

            instructionDefOperandCount.put(inst, operandDefPosition);
            instructionUseOperandCount.put(inst, operandUsePosition);
        }
    }

    private void initializeCollections() {
        this.duPairs = new ArrayList<>();
        this.duSequences = new ArrayList<>();
        this.duSequenceWebs = new ArrayList<>();

        instructionDefOperandCount = new IdentityHashMap<>();
        instructionUseOperandCount = new IdentityHashMap<>();
    }

    public static <T, U, V> Map<U, List<V>> mergeMaps(Map<T, Map<U, List<V>>> map, T[] mergeKeys, Comparator<? super U> comparator) {
        Map<U, List<V>> mergedMap = new TreeMap<>(comparator);

        for (T mergeKey : mergeKeys) {
            Map<U, List<V>> mergeValueMap = map.get(mergeKey);

            for (Entry<U, List<V>> entry : mergeValueMap.entrySet()) {
                List<V> mergedMapValue = mergedMap.get(entry.getKey());

                if (mergedMapValue == null) {
                    mergedMapValue = new ArrayList<>();
                    mergedMap.put(entry.getKey(), mergedMapValue);
                }

                List<V> newValues = entry.getValue().stream().filter(x -> !(mergedMap.get(entry.getKey()).contains(x))).collect(Collectors.toList());
                mergedMapValue.addAll(newValues);
            }
        }
        return mergedMap;
    }

    private void analyseUndefinedValues(Map<Value, List<ValUsage>> valUseInstructions, RegisterAttributes[] registerAttributes, Map<Register, DummyDef> dummyDefs) {
        checkUndefinedValues(valUseInstructions, registerAttributes, dummyDefs);
        List<LIRInstruction> dummyDefsList = dummyDefs.values().stream().collect(Collectors.toList());
        determineDuSequenceWebs(dummyDefsList, valUseInstructions);
    }

    private static void checkUndefinedValues(Map<Value, List<ValUsage>> valUseInstructions, RegisterAttributes[] registerAttributes, Map<Register, DummyDef> dummyDefs) {
        for (Value value : valUseInstructions.keySet()) {
            if (LIRValueUtil.isJavaConstant(value)) {
                // value is constant value
                // TODO: primitive value
            } else if (!ValueUtil.isRegister(value)) {
                GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used value " + value + " is not defined.");
            } else {
                Register register = ValueUtil.asRegister(value);
                if (registerAttributes[register.number].isAllocatable()) {
                    GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used register " + register + " is not defined.");
                }
                // TODO: insert of dummy instruction for non-allocatable register
                DummyDef dummyDef = dummyDefs.get(register);
                if (dummyDef == null) {
                    dummyDefs.put(register, new DummyDef(register.asValue()));
                }
            }
        }
    }

    private static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer, InstructionValueConsumer aliveConsumer) {
        instruction.visitEachOutput(defConsumer);
        instruction.visitEachInput(useConsumer);
        instruction.visitEachAlive(aliveConsumer);
    }

    class ValUsage {
        private LIRInstruction useInstruction;
        private int operandPosition;

        public ValUsage(LIRInstruction useInstruction, int operandPosition) {
            this.useInstruction = useInstruction;
            this.operandPosition = operandPosition;
        }

        public LIRInstruction getUseInstruction() {
            return useInstruction;
        }

        public int getOperandPosition() {
            return operandPosition;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ValUsage) {
                ValUsage valUsage = (ValUsage) obj;
                return this.useInstruction.equals(valUsage.getUseInstruction()) && this.operandPosition == valUsage.getOperandPosition();
            }
            return false;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    class DefInstructionValueConsumer implements InstructionValueConsumer {

        private Map<Value, List<ValUsage>> valUseInstructions;

        public DefInstructionValueConsumer(Map<Value, List<ValUsage>> valUseInstructions) {
            this.valUseInstructions = valUseInstructions;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                operandDefPosition++;
                return;
            }

            List<ValUsage> useInstructions = valUseInstructions.get(value);
            if (useInstructions == null) {
                // definition of a value, which is not used
                operandDefPosition++;
                return;
            }

            AllocatableValue allocatableValue = ValueUtil.asAllocatableValue(value);
            DuSequenceWeb duSequenceWeb = new DuSequenceWeb();

            for (ValUsage valUsage : useInstructions) {
                DuPair duPair = new DuPair(allocatableValue, instruction, valUsage.getUseInstruction(),
                                operandDefPosition, valUsage.getOperandPosition());
                duPairs.add(duPair);

                if (valUsage.getUseInstruction().isValueMoveOp()) {
                    // copy use instruction
                    duSequences.stream().filter(duSequence -> duSequence.peekFirst().getDefInstruction().equals(valUsage.getUseInstruction())).forEach(x -> x.addFirst(duPair));
                } else {
                    // non copy use instruction
                    DuSequence duSequence = new DuSequence(duPair);
                    duSequences.add(duSequence);
                    duSequenceWeb.add(duSequence);
                }
            }

            if (duSequenceWeb.size() > 0) {
                duSequenceWebs.add(duSequenceWeb);
            }

            valUseInstructions.remove(value);

            operandDefPosition++;
        }

    }

    class UseInstructionValueConsumer implements InstructionValueConsumer {

        private Map<Value, List<ValUsage>> valUseInstructions;

        public UseInstructionValueConsumer(Map<Value, List<ValUsage>> valUseInstructions) {
            this.valUseInstructions = valUseInstructions;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            // TODO: skip primitive constants and object[null]
            if (ValueUtil.isIllegal(value) || flags.contains(OperandFlag.UNINITIALIZED)) {
                // value is part of a composite value or is uninitialized
                operandUsePosition++;
                return;
            }

            List<ValUsage> useInstructions = valUseInstructions.get(value);

            if (useInstructions == null) {
                useInstructions = new ArrayList<>();
            }
            useInstructions.add(new ValUsage(instruction, operandUsePosition));
            valUseInstructions.put(value, useInstructions);

            operandUsePosition++;
        }

    }

}
