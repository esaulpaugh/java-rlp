package org.tdf.rlp;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RLPDeserializer{

    public static <T> T deserialize(byte[] data, Class<T> clazz) {
        RLPElement element = RLPElement.fromEncoded(data);
        return deserialize(element, clazz);
    }

    public static <T> List<T> deserializeList(byte[] data, Class<T> elementType) {
        RLPElement element = RLPElement.fromEncoded(data);
        return deserializeList(element.getAsList(), elementType);
    }

    private static <T> List<T> deserializeList(RLPList list, Class<T> elementType) {
        if (elementType == RLPElement.class) return (List<T>) list;
        if (elementType == RLPItem.class) {
            return (List<T>) list.stream().map(x -> x.getAsItem()).collect(Collectors.toList());
        }
        List<T> res = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            res.add(deserialize(list.get(i), elementType));
        }
        return res;
    }

    public static <T> T deserialize(RLPElement element, Class<T> clazz) {
        if (clazz == RLPElement.class) return (T) element;
        if (clazz == RLPList.class) return (T) element.getAsList();
        if (clazz == RLPItem.class) return (T) element.getAsItem();
        RLPDecoder decoder = RLPUtils.getAnnotatedRLPDecoder(clazz);
        if (decoder != null) return (T) decoder.decode(element);
        // non null terminals
        if (clazz == Byte.class || clazz == byte.class) {
            return (T) Byte.valueOf(element.getAsItem().getByte());
        }
        if (clazz == Short.class || clazz == short.class) {
            return (T) Short.valueOf(element.getAsItem().getShort());
        }
        if (clazz == Integer.class || clazz == int.class) {
            return (T) Integer.valueOf(element.getAsItem().getInt());
        }
        if (clazz == Long.class || clazz == long.class) {
            return (T) Long.valueOf(element.getAsItem().getLong());
        }
        if (clazz == byte[].class) {
            return (T) element.getAsItem().get();
        }
        // String is non-null, since we cannot differ between null empty string and null
        if (clazz == String.class) {
            return (T) element.getAsItem().getString();
        }
        // big integer is non-null, since we cannot differ between zero and null
        if (clazz == BigInteger.class) {
            return (T) element.getAsItem().getBigInteger();
        }
        if (element.isNull()) return null;
        if (clazz.isArray()) {
            Class elementType = clazz.getComponentType();
            Object res = Array.newInstance(clazz.getComponentType(), element.getAsList().size());
            for (int i = 0; i < element.getAsList().size(); i++) {
                Array.set(res, i, deserialize(element.getAsList().get(i), elementType));
            }
            return (T) res;
        }
        // cannot determine generic type at runtime
        if (clazz == List.class) {
            return (T) deserializeList(element.getAsList(), RLPElement.class);
        }
        Object o;
        try {
            o = clazz.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        List<Field> fields = RLPUtils.getRLPFields(clazz);
        if (fields.size() == 0) throw new RuntimeException(clazz + " is not supported not RLP annotation found");
        for (int i = 0; i < fields.size(); i++) {
            RLPElement el = element.getAsList().get(i);
            Field f = fields.get(i);
            f.setAccessible(true);
            RLPDecoder fieldDecoder = RLPUtils.getAnnotatedRLPDecoder(f);
            if (fieldDecoder != null) {
                try {
                    f.set(o, fieldDecoder.decode(el));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                continue;
            }
            if (!f.getType().equals(List.class)) {
                try {
                    f.set(o, deserialize(el, f.getType()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                continue;
            }
            ParameterizedType parameterizedType = (ParameterizedType)f.getGenericType();
            Type[] types =  parameterizedType.getActualTypeArguments();


            try {
                if (el.isNull()) {
                    f.set(o, null);
                    continue;
                }
                f.set(o, deserializeList(el.getAsList(), (Class)types[0]));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return (T) o;
    }
}
