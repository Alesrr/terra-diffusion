package com.github.xandergos.terraindiffusionmc.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HalfPrecisionModel {

    private static final int FLOAT = 1;
    private static final int INT64 = 7;
    private static final int BOOL = 9;
    private static final int FLOAT16 = 10;
    private static final float HALF_MAX = 65504f;

    private HalfPrecisionModel() {
    }

    private record Field(int number, int wire, int start, int payload, int end, long value) {
    }

    private record Node(int start, int end, String op, List<String> inputs, List<String> outputs,
                        Integer castTo, Integer valueType, boolean hasValue) {
    }

    static void convert(byte[] model, OutputStream out) throws IOException {
        for (Field f : fields(model, 0, model.length)) {
            if (f.number() == 7 && f.wire() == 2) {
                Sink graph = graph(model, f.payload(), f.end());
                Sink header = new Sink();
                header.tag(7, 2);
                header.varint(graph.size());
                header.writeTo(out);
                graph.writeTo(out);
            } else {
                out.write(model, f.start(), f.end() - f.start());
            }
        }
    }

    private static Sink graph(byte[] b, int start, int end) {
        List<Field> graphFields = fields(b, start, end);
        Map<String, Integer> types = new HashMap<>();
        for (Field f : graphFields) {
            if (f.number() == 5) {
                String name = "";
                int type = 0;
                for (Field t : fields(b, f.payload(), f.end())) {
                    if (t.number() == 8) name = string(b, t);
                    if (t.number() == 2) type = (int) t.value();
                }
                types.put(name, type == FLOAT ? FLOAT16 : type);
            }
        }
        Map<String, String> renamedInputs = new HashMap<>();
        Map<String, String> renamedOutputs = new HashMap<>();
        Sink body = new Sink();
        List<byte[]> outputCasts = new ArrayList<>();
        for (Field f : graphFields) {
            if (f.number() != 11 && f.number() != 12) continue;
            String name = "";
            int elemType = 0;
            for (Field v : fields(b, f.payload(), f.end())) {
                if (v.number() == 1) name = string(b, v);
                if (v.number() == 2) {
                    for (Field t : fields(b, v.payload(), v.end())) {
                        if (t.number() != 1) continue;
                        for (Field e : fields(b, t.payload(), t.end())) {
                            if (e.number() == 1) elemType = (int) e.value();
                        }
                    }
                }
            }
            if (elemType != FLOAT) continue;
            if (f.number() == 11 && !types.containsKey(name)) {
                String renamed = name + "__half";
                renamedInputs.put(name, renamed);
                types.put(renamed, FLOAT16);
                body.field(1, castNode("half_in_" + name, name, renamed, FLOAT16));
            } else if (f.number() == 12) {
                String renamed = name + "__half";
                renamedOutputs.put(name, renamed);
                outputCasts.add(castNode("half_out_" + name, renamed, name, FLOAT));
            }
        }
        List<Node> nodes = new ArrayList<>();
        for (Field f : graphFields) {
            if (f.number() == 1) nodes.add(node(b, f));
        }
        Set<Integer> keep = keptNodes(nodes);
        Map<String, String> fullPrecision = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            List<String> inputs = new ArrayList<>();
            for (String in : n.inputs()) inputs.add(renamedInputs.getOrDefault(in, in));
            List<String> outputs = new ArrayList<>();
            for (String o : n.outputs()) outputs.add(renamedOutputs.getOrDefault(o, o));
            Integer outType = outputType(n, inputs, types);
            for (String o : outputs) types.put(o, outType);
            boolean full = keep.contains(i) && inputs.stream().anyMatch(in -> isHalf(types, in));
            if (!full) {
                body.field(1, rewriteNode(b, n, inputs, outputs, true));
                continue;
            }
            List<String> fullInputs = new ArrayList<>();
            for (String in : inputs) {
                if (!isHalf(types, in)) {
                    fullInputs.add(in);
                    continue;
                }
                String widened = fullPrecision.get(in);
                if (widened == null) {
                    widened = in + "__full" + i;
                    body.field(1, castNode("half_keep_in_" + i + "_" + fullInputs.size(), in, widened, FLOAT));
                }
                fullInputs.add(widened);
            }
            List<String> fullOutputs = new ArrayList<>();
            List<byte[]> narrowing = new ArrayList<>();
            for (String o : outputs) {
                if (!isHalf(types, o)) {
                    fullOutputs.add(o);
                    continue;
                }
                String widened = o + "__full";
                fullPrecision.put(o, widened);
                fullOutputs.add(widened);
                narrowing.add(castNode("half_keep_out_" + i + "_" + narrowing.size(), widened, o, FLOAT16));
            }
            body.field(1, rewriteNode(b, n, fullInputs, fullOutputs, false));
            for (byte[] c : narrowing) body.field(1, c);
        }
        for (byte[] c : outputCasts) body.field(1, c);
        for (Field f : graphFields) {
            switch (f.number()) {
                case 1, 13 -> {
                }
                case 5 -> body.field(5, tensor(b, f.payload(), f.end()));
                default -> body.write(b, f.start(), f.end() - f.start());
            }
        }
        return body;
    }

    private static boolean isHalf(Map<String, Integer> types, String name) {
        return !name.isEmpty() && Integer.valueOf(FLOAT16).equals(types.get(name));
    }

    private static Set<Integer> keptNodes(List<Node> nodes) {
        Map<String, List<Integer>> consumers = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            for (String in : nodes.get(i).inputs()) consumers.computeIfAbsent(in, k -> new ArrayList<>()).add(i);
        }
        Set<Integer> keep = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node pow = nodes.get(i);
            if (pow.op().equals("ReduceL2")) {
                keep.add(i);
                continue;
            }
            if (!pow.op().equals("Pow") || pow.inputs().isEmpty() || pow.outputs().isEmpty()) continue;
            Integer mean = consumer(nodes, consumers, pow, "ReduceMean");
            Integer add = mean == null ? null : consumer(nodes, consumers, nodes.get(mean), "Add");
            Integer sqrt = add == null ? null : consumer(nodes, consumers, nodes.get(add), "Sqrt");
            Integer div = sqrt == null ? null : consumer(nodes, consumers, nodes.get(sqrt), "Div");
            if (div == null) continue;
            List<String> divInputs = nodes.get(div).inputs();
            if (divInputs.size() == 2 && divInputs.get(0).equals(pow.inputs().get(0))
                    && divInputs.get(1).equals(nodes.get(sqrt).outputs().get(0))) {
                keep.addAll(List.of(i, mean, add, sqrt, div));
            }
        }
        return keep;
    }

    private static Integer consumer(List<Node> nodes, Map<String, List<Integer>> consumers, Node producer, String op) {
        if (producer.outputs().isEmpty()) return null;
        for (Integer c : consumers.getOrDefault(producer.outputs().get(0), List.of())) {
            if (nodes.get(c).op().equals(op)) return c;
        }
        return null;
    }

    private static Integer outputType(Node n, List<String> inputs, Map<String, Integer> types) {
        return switch (n.op()) {
            case "Shape", "Size", "NonZero", "ArgMax", "ArgMin" -> INT64;
            case "Cast" -> n.castTo() != null && n.castTo() == FLOAT ? Integer.valueOf(FLOAT16) : n.castTo();
            case "Constant", "ConstantOfShape" -> n.valueType() == null || n.valueType() == FLOAT ? Integer.valueOf(FLOAT16) : n.valueType();
            case "Equal", "Less", "Greater", "LessOrEqual", "GreaterOrEqual", "Not", "And", "Or", "IsNaN", "IsInf" -> BOOL;
            case "Where" -> inputs.size() > 1 ? types.get(inputs.get(1)) : null;
            default -> inputs.isEmpty() ? null : types.get(inputs.get(0));
        };
    }

    private static Node node(byte[] b, Field nf) {
        String op = "";
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        Integer castTo = null;
        Integer valueType = null;
        boolean hasValue = false;
        for (Field f : fields(b, nf.payload(), nf.end())) {
            switch (f.number()) {
                case 1 -> inputs.add(string(b, f));
                case 2 -> outputs.add(string(b, f));
                case 4 -> op = string(b, f);
                case 5 -> {
                    String name = "";
                    Integer i = null;
                    Integer t = null;
                    for (Field a : fields(b, f.payload(), f.end())) {
                        if (a.number() == 1) name = string(b, a);
                        if (a.number() == 3 && a.wire() == 0) i = (int) a.value();
                        if (a.number() == 5 && a.wire() == 2) {
                            for (Field tf : fields(b, a.payload(), a.end())) {
                                if (tf.number() == 2) t = (int) tf.value();
                            }
                        }
                    }
                    if (name.equals("to")) castTo = i;
                    if (name.equals("value")) {
                        hasValue = true;
                        valueType = t;
                    }
                }
                default -> {
                }
            }
        }
        return new Node(nf.payload(), nf.end(), op, inputs, outputs, castTo, valueType, hasValue);
    }

    private static byte[] rewriteNode(byte[] b, Node n, List<String> inputs, List<String> outputs, boolean convert) {
        Sink s = new Sink();
        int in = 0;
        int out = 0;
        for (Field f : fields(b, n.start(), n.end())) {
            switch (f.number()) {
                case 1 -> s.field(1, utf8(inputs.get(in++)));
                case 2 -> s.field(2, utf8(outputs.get(out++)));
                case 5 -> s.field(5, convert ? attribute(b, f, n.op()) : Arrays.copyOfRange(b, f.payload(), f.end()));
                default -> s.write(b, f.start(), f.end() - f.start());
            }
        }
        if (convert && n.op().equals("ConstantOfShape") && !n.hasValue()) {
            Sink t = new Sink();
            t.tag(1, 0);
            t.varint(1);
            t.tag(2, 0);
            t.varint(FLOAT16);
            t.field(9, new byte[2]);
            Sink a = new Sink();
            a.field(1, utf8("value"));
            a.field(5, t.toByteArray());
            a.tag(20, 0);
            a.varint(4);
            s.field(5, a.toByteArray());
        }
        return s.toByteArray();
    }

    private static byte[] attribute(byte[] b, Field attr, String op) {
        List<Field> fs = fields(b, attr.payload(), attr.end());
        String name = "";
        for (Field f : fs) {
            if (f.number() == 1) name = string(b, f);
        }
        Sink s = new Sink();
        for (Field f : fs) {
            if (f.number() == 3 && f.wire() == 0 && op.equals("Cast") && name.equals("to") && f.value() == FLOAT) {
                s.tag(3, 0);
                s.varint(FLOAT16);
            } else if (f.number() == 5 && f.wire() == 2) {
                s.field(5, tensor(b, f.payload(), f.end()));
            } else if (op.equals("Constant") && (f.number() == 2 || f.number() == 7)) {
                throw new IllegalArgumentException("Unsupported float constant attribute '" + name + "'");
            } else {
                s.write(b, f.start(), f.end() - f.start());
            }
        }
        return s.toByteArray();
    }

    private static byte[] tensor(byte[] b, int start, int end) {
        List<Field> fs = fields(b, start, end);
        long type = -1;
        for (Field f : fs) {
            if (f.number() == 2) type = f.value();
            if (f.number() == 13 || (f.number() == 14 && f.value() == 1)) {
                throw new IllegalArgumentException("External tensor data is not supported");
            }
        }
        if (type != FLOAT) return Arrays.copyOfRange(b, start, end);
        Sink s = new Sink();
        for (Field f : fs) {
            switch (f.number()) {
                case 2 -> {
                    s.tag(2, 0);
                    s.varint(FLOAT16);
                }
                case 4, 9 -> {
                }
                default -> s.write(b, f.start(), f.end() - f.start());
            }
        }
        s.field(9, half(b, fs));
        return s.toByteArray();
    }

    private static byte[] half(byte[] b, List<Field> fs) {
        ByteBuffer le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        for (Field f : fs) {
            if (f.number() == 9 || (f.number() == 4 && f.wire() == 2)) count += (f.end() - f.payload()) / 4;
            else if (f.number() == 4 && f.wire() == 5) count++;
        }
        byte[] out = new byte[count * 2];
        int k = 0;
        for (Field f : fs) {
            if (f.number() == 9 || (f.number() == 4 && f.wire() == 2)) {
                for (int p = f.payload(); p + 4 <= f.end(); p += 4) k = putHalf(out, k, le.getFloat(p));
            } else if (f.number() == 4 && f.wire() == 5) {
                k = putHalf(out, k, le.getFloat(f.payload()));
            }
        }
        return out;
    }

    private static int putHalf(byte[] out, int k, float v) {
        float clamped = Float.isNaN(v) ? v : Math.max(-HALF_MAX, Math.min(HALF_MAX, v));
        short h = Float.floatToFloat16(clamped);
        out[k] = (byte) h;
        out[k + 1] = (byte) (h >> 8);
        return k + 2;
    }

    private static byte[] castNode(String name, String from, String to, int type) {
        Sink a = new Sink();
        a.field(1, utf8("to"));
        a.tag(3, 0);
        a.varint(type);
        a.tag(20, 0);
        a.varint(2);
        Sink s = new Sink();
        s.field(1, utf8(from));
        s.field(2, utf8(to));
        s.field(3, utf8(name));
        s.field(4, utf8("Cast"));
        s.field(5, a.toByteArray());
        return s.toByteArray();
    }

    private static List<Field> fields(byte[] b, int start, int end) {
        List<Field> out = new ArrayList<>();
        int[] pos = {start};
        while (pos[0] < end) {
            int fieldStart = pos[0];
            long key = varint(b, pos);
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            int payload = pos[0];
            long value = 0;
            switch (wire) {
                case 0 -> value = varint(b, pos);
                case 1 -> pos[0] += 8;
                case 2 -> {
                    long length = varint(b, pos);
                    payload = pos[0];
                    pos[0] += (int) length;
                }
                case 5 -> pos[0] += 4;
                default -> throw new IllegalArgumentException("Unsupported protobuf wire type " + wire);
            }
            out.add(new Field(number, wire, fieldStart, payload, pos[0], value));
        }
        return out;
    }

    private static long varint(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte c = b[pos[0]++];
            result |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return result;
            shift += 7;
        }
    }

    private static String string(byte[] b, Field f) {
        return new String(b, f.payload(), f.end() - f.payload(), StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Sink {
        private final List<byte[]> chunks = new ArrayList<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long size;

        void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
            size += len;
        }

        void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                buffer.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
                size++;
            }
            buffer.write((int) v);
            size++;
        }

        void tag(int number, int wire) {
            varint(((long) number << 3) | wire);
        }

        void field(int number, byte[] payload) {
            tag(number, 2);
            varint(payload.length);
            if (payload.length >= 1 << 16) {
                flush();
                chunks.add(payload);
                size += payload.length;
            } else {
                write(payload, 0, payload.length);
            }
        }

        long size() {
            return size;
        }

        private void flush() {
            if (buffer.size() > 0) {
                chunks.add(buffer.toByteArray());
                buffer.reset();
            }
        }

        byte[] toByteArray() {
            flush();
            byte[] out = new byte[Math.toIntExact(size)];
            int k = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, out, k, c.length);
                k += c.length;
            }
            return out;
        }

        void writeTo(OutputStream out) throws IOException {
            flush();
            for (byte[] c : chunks) out.write(c);
        }
    }
}
