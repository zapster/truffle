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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.ssa.SSAUtil;
import org.graalvm.compiler.lir.ssa.SSAUtil.PhiValueVisitor;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DuSequenceAnalysis {
// test
    public static class DummyConstDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyConstDef> TYPE = LIRInstructionClass.create(DummyConstDef.class);
        protected ConstantValue value;

        public DummyConstDef(ConstantValue value) {
            super(TYPE);
            this.value = value;
        }

        public ConstantValue getValue() {
            return value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

    }

    public static class DummyRegDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyRegDef> TYPE = LIRInstructionClass.create(DummyRegDef.class);
        @Def({REG}) protected AllocatableValue value;

        public DummyRegDef(AllocatableValue value) {
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

    public static final CounterKey skippedCompilationUnits = DebugContext.counter("SARAVerify[skipped]");
    public static final CounterKey executedCompilationUnits = DebugContext.counter("SARAVerify[executed]");

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

    public AnalysisResult determineDuSequenceWebs(LIRGenerationResult lirGenRes, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        LIR lir = lirGenRes.getLIR();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();
        BitSet blockQueue = new BitSet(blocks.length);

        if (!(lir.getControlFlowGraph().getLoops().isEmpty())) {
            // control flow contains one or more loops
            skippedCompilationUnits.increment(lir.getDebug());
            return null;
        }
        executedCompilationUnits.increment(lir.getDebug());

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
        HashMap<AbstractBlockBase<?>, List<DuSequence>> blockUnfinishedDuSequences = new HashMap<>();

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

            // merging of value uses from multiple predecessors
            Map<Value, List<ValUsage>> valUseInstructions = mergeMaps(blockValUseInstructions, block.getSuccessors(), saraVerifyValueComparator);
            blockValUseInstructions.put(block, valUseInstructions);

            // merging of unfinished du-sequences from multiple predecessors
            List<DuSequence> unfinishedDuSequences = mergeDuSequencesLists(blockUnfinishedDuSequences, block.getSuccessors());
            blockUnfinishedDuSequences.put(block, unfinishedDuSequences);

            determineDuSequenceWebs(lir, block, instructions, valUseInstructions, unfinishedDuSequences, dummyConstDefs);
        }
        assert visitedBlocks.length() == visitedBlocks.cardinality() && visitedBlocks.cardinality() == blocks.length && visitedBlocks.stream().allMatch(id -> id < blocks.length);

        // analysis of dummy instructions
        analyseUndefinedValues(blockValUseInstructions.get(blocks[0]), blockUnfinishedDuSequences.get(blocks[0]), registerAttributes, dummyRegDefs, dummyConstDefs);

        return new AnalysisResult(duPairs, duSequences, duSequenceWebs, instructionDefOperandCount, instructionUseOperandCount, dummyRegDefs, dummyConstDefs);
    }

    public AnalysisResult determineDuSequenceWebs(List<LIRInstruction> instructions, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        initializeCollections();

        // analysis of given instructions
        Map<Value, List<ValUsage>> valUseInstructions = new TreeMap<>(new SARAVerifyValueComparator());
        LinkedList<DuSequence> unfinishedDuSequences = new LinkedList<>();
        determineDuSequenceWebs(null, null, instructions, valUseInstructions, unfinishedDuSequences, dummyConstDefs);

        // analysis of dummy instructions
        analyseUndefinedValues(valUseInstructions, unfinishedDuSequences, registerAttributes, dummyRegDefs, dummyConstDefs);

        return new AnalysisResult(duPairs, duSequences, duSequenceWebs, instructionDefOperandCount, instructionUseOperandCount, dummyRegDefs, dummyConstDefs);
    }

    private void determineDuSequenceWebs(LIR lir, AbstractBlockBase<?> block, List<LIRInstruction> instructions, Map<Value, List<ValUsage>> valUseInstructions,
                    List<DuSequence> unfinishedDuSequences,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(valUseInstructions, unfinishedDuSequences);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(valUseInstructions);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            operandDefPosition = 0;
            operandUsePosition = 0;

            // phi
            if (inst instanceof JumpOp) {
                JumpOp jumpInst = (JumpOp) inst;

                if (jumpInst.getPhiSize() > 0) {
                    PhiValueVisitor visitor = new SARAVerifyPhiValueVisitor(lir, jumpInst, dummyConstDefs, unfinishedDuSequences);
                    SSAUtil.forEachPhiValuePair(lir, block.getSuccessors()[0], block, visitor);
                }
            }

            visitValues(inst, defConsumer, useConsumer, useConsumer);

            instructionDefOperandCount.put(inst, operandDefPosition);
            instructionUseOperandCount.put(inst, operandUsePosition);

            if (inst.isLoadConstantOp()) {
                // insert du-pair from dummy constant instruction to current instruction
                Constant constant = ((LoadConstantOp) inst).getConstant();

                DummyConstDef dummyConstDef = dummyConstDefs.get(constant);
                if (dummyConstDef == null) {
                    dummyConstDef = new DummyConstDef(new ConstantValue(ValueKind.Illegal, constant));
                    dummyConstDefs.put(constant, dummyConstDef);
                }

                ConstantValue constantValue = dummyConstDef.getValue();
                DuPair duPairConstMove = new DuPair(constantValue, dummyConstDef, inst, 0, 0);
                duPairs.add(duPairConstMove);

                // TODO: remove from unfinishedDuSequences
                duSequences.stream().filter(duSequence -> duSequence.peekFirst().getDefInstruction().equals(inst)).forEach(x -> x.addFirst(duPairConstMove));
            }
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

    private static List<DuSequence> mergeDuSequencesLists(Map<AbstractBlockBase<?>, List<DuSequence>> blockDuSequences, AbstractBlockBase<?>[] mergeBlocks) {
        List<DuSequence> mergedDuSequences = new ArrayList<>();

        for (AbstractBlockBase<?> block : mergeBlocks) {
            List<DuSequence> duSequencesList = blockDuSequences.get(block);

            duSequencesList.stream().forEach(duSequence -> mergedDuSequences.add(new DuSequence(duSequence.cloneDuPairs())));
        }

        return mergedDuSequences.stream().distinct().collect(Collectors.toList());
    }

    private void analyseUndefinedValues(Map<Value, List<ValUsage>> valUseInstructions, List<DuSequence> unfinishedDuSequences, RegisterAttributes[] registerAttributes,
                    Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        checkUndefinedValues(valUseInstructions, registerAttributes, dummyRegDefs);
        List<LIRInstruction> dummyRegDefsList = dummyRegDefs.values().stream().collect(Collectors.toList());
        determineDuSequenceWebs(null, null, dummyRegDefsList, valUseInstructions, unfinishedDuSequences, dummyConstDefs);
        assert valUseInstructions.isEmpty();
    }

    private static void checkUndefinedValues(Map<Value, List<ValUsage>> valUseInstructions, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs) {

        for (Value value : valUseInstructions.keySet()) {
            if (!ValueUtil.isRegister(value)) {
                GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used value " + value + " is not defined.");
            }

            Register register = ValueUtil.asRegister(value);
            if (registerAttributes[register.number].isAllocatable()) {
                GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used register " + register + " is not defined.");
            }

            DummyRegDef dummyDef = dummyRegDefs.get(register);
            if (dummyDef == null) {
                dummyRegDefs.put(register, new DummyRegDef(register.asValue()));
            }
        }
    }

    private static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer, InstructionValueConsumer aliveConsumer) {
        // TODO: instruction.isMoveOp

        instruction.visitEachAlive(aliveConsumer);
        // TODO: instruction.forEachState(proc); alive

        // TODO: instruction.visitEachTemp(proc);
        // TODO: instruction.destroysCallerSavedRegisters()
        // TODO: for each caller saved register a dummy def
        instruction.visitEachOutput(defConsumer);
        instruction.visitEachInput(useConsumer);
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
        private List<DuSequence> unfinishedDuSequences;

        public DefInstructionValueConsumer(Map<Value, List<ValUsage>> valUseInstructions, List<DuSequence> unfinishedDuSequences) {
            this.valUseInstructions = valUseInstructions;
            this.unfinishedDuSequences = unfinishedDuSequences;
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

                if (valUsage.getUseInstruction().isValueMoveOp() || valUsage.getUseInstruction() instanceof JumpOp) {
                    // copy use instruction or jump instruction
                    Supplier<Stream<DuSequence>> copyDuSequencesStreamSupplier = () -> unfinishedDuSequences.stream().filter(
                                    duSequence -> duSequence.peekFirst().getDefInstruction().equals(valUsage.getUseInstruction()) &&
                                                    duSequence.peekFirst().getOperandDefPosition() == valUsage.operandPosition);

                    if (instruction.isValueMoveOp() || (instruction instanceof LabelOp && ((LabelOp) instruction).isPhiIn())) {
                        copyDuSequencesStreamSupplier.get().forEach(duSequence -> duSequence.addFirst(duPair));
                    } else {
                        copyDuSequencesStreamSupplier.get().forEach(duSequence -> duSequences.add(duSequence));
                        copyDuSequencesStreamSupplier.get().forEach(duSequence -> duSequence.addFirst(duPair));
                        // TODO: remove from unfinishedDuSequences
                    }
                } else {
                    // non copy use instruction
                    DuSequence duSequence = new DuSequence(duPair);

                    if (instruction.isValueMoveOp() || (instruction instanceof LabelOp && ((LabelOp) instruction).isPhiIn())) {
                        // copy def instruction
                        unfinishedDuSequences.add(duSequence);
                    } else {
                        // non copy def instruction
                        duSequences.add(duSequence);
                    }
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
            if (ValueUtil.isIllegal(value) || flags.contains(OperandFlag.UNINITIALIZED) || LIRValueUtil.isConstantValue(value)) {
                // value is part of a composite value, uninitialized or a constant
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

    class SARAVerifyPhiValueVisitor implements PhiValueVisitor {

        private LIR lir;
        private JumpOp jumpInst;
        private Map<Constant, DummyConstDef> dummyConstDefs;
        private int operandPhiPos;
        private List<DuSequence> unfinishedDuSequences;

        public SARAVerifyPhiValueVisitor(LIR lir, JumpOp jumpInst, Map<Constant, DummyConstDef> dummyConstDefs, List<DuSequence> unfinishedDuSequences) {
            this.lir = lir;
            this.jumpInst = jumpInst;
            this.dummyConstDefs = dummyConstDefs;
            operandPhiPos = 0;
            this.unfinishedDuSequences = unfinishedDuSequences;
        }

        @Override
        public void visit(Value phiIn, Value phiOut) {
            LIRInstruction targetLabelInst = lir.getLIRforBlock(jumpInst.destination().getTargetBlock()).get(0);

            assert targetLabelInst instanceof LabelOp;
            assert ((LabelOp) targetLabelInst).getIncomingValue(operandPhiPos).equals(phiIn);
            assert jumpInst.getOutgoingValue(operandPhiPos).equals(phiOut);

            DuPair duPair = new DuPair(phiOut, jumpInst, targetLabelInst, operandPhiPos, operandPhiPos);
            duPairs.add(duPair);

            List<DuSequence> phiDuSequences = unfinishedDuSequences.stream().filter(duSequence -> duSequence.peekFirst().getDefInstruction().equals(duPair.getUseInstruction()) &&
                            duSequence.peekFirst().getOperandDefPosition() == operandPhiPos).distinct().collect(
                                            Collectors.toList());
            phiDuSequences.stream().forEach(x -> x.addFirst(duPair));

            if (LIRValueUtil.isConstantValue(phiOut)) {
                Constant constant = ((ConstantValue) phiOut).getConstant();
                DummyConstDef dummyConstDef = dummyConstDefs.get(constant);
                if (dummyConstDef == null) {
                    dummyConstDef = new DummyConstDef(new ConstantValue(ValueKind.Illegal, constant));
                    dummyConstDefs.put(constant, dummyConstDef);
                }

                DuPair constJumpDuPair = new DuPair(dummyConstDef.getValue(), dummyConstDef, jumpInst, 0, operandPhiPos);
                duPairs.add(constJumpDuPair);
                phiDuSequences.stream().forEach(x -> x.addFirst(constJumpDuPair));
                duSequences.addAll(phiDuSequences);
            }

            operandPhiPos++;
        }
    }

}
