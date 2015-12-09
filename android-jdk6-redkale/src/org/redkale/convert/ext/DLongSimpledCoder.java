/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.convert.ext;

import org.redkale.convert.Reader;
import org.redkale.convert.Writer;
import org.redkale.convert.SimpledCoder;
import org.redkale.util.DLong;

/**
 *
 * @author zhangjx
 * @param <R>
 * @param <W>
 */
public final class DLongSimpledCoder<R extends Reader, W extends Writer> extends SimpledCoder<R, W, DLong> {

    public static final DLongSimpledCoder instance = new DLongSimpledCoder();

    @Override
    public void convertTo(final W out, final DLong value) {
        if (value == null) {
            out.writeNull();
        } else {
            out.writeSmallString(value.getFirst() + "_" + value.getSecond());
        }
    }

    @Override
    public DLong convertFrom(R in) {
        String str = in.readString();
        if (str == null) return null;
        int pos = str.indexOf('_');
        return new DLong(Long.parseLong(str.substring(0, pos)), Long.parseLong(str.substring(pos + 1)));
    }

}
