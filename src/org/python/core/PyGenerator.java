/* Copyright (c) Jython Developers */
package org.python.core;

import org.python.core.finalization.FinalizableBuiltin;
import org.python.core.finalization.FinalizeTrigger;
import org.python.expose.ExposedGet;
import org.python.expose.ExposedMethod;
import org.python.expose.ExposedType;

@ExposedType(name = "generator", base = PyObject.class, isBaseType = false, doc = BuiltinDocs.generator_doc)
public class PyGenerator extends PyIterator implements FinalizableBuiltin {

    public static final PyType TYPE = PyType.fromClass(PyGenerator.class);

    @ExposedGet
    protected PyFrame gi_frame;

    @ExposedGet
    protected PyCode gi_code = null;

    @ExposedGet
    protected boolean gi_running;

    private PyObject closure;

    public PyGenerator(PyFrame frame, PyObject closure) {
        this(TYPE, frame, closure);
    }

    public PyGenerator(PyType subType, PyFrame frame, PyObject closure) {
        super(subType);
        gi_frame = frame;
        if (gi_frame != null) {
            gi_code = gi_frame.f_code;
        }
        this.closure = closure;
        FinalizeTrigger.ensureFinalizer(this);
    }

    public PyObject send(PyObject value) {
        return generator_send(value);
    }

    @ExposedMethod(doc = BuiltinDocs.generator_send_doc)
    final PyObject generator_send(PyObject value) {
        if (gi_frame == null) {
            throw Py.StopIteration();
        }

        if (gi_frame.f_lasti == 0 && value != Py.None && value != null) {
            throw Py.TypeError("can't send non-None value to a just-started generator");
        }
        return gen_send_ex(Py.getThreadState(), value);
    }

    public PyObject throw$(PyObject type, PyObject value, PyObject tb) {
        return generator_throw$(type, value, tb);
    }

    @ExposedMethod(names="throw", defaults={"null", "null"}, doc = BuiltinDocs.generator_throw_doc)
    final PyObject generator_throw$(PyObject type, PyObject value, PyObject tb) {
        if (tb == Py.None) {
            tb = null;
        } else if (tb != null && !(tb instanceof PyTraceback)) {
            throw Py.TypeError("throw() third argument must be a traceback object");
        }

        if (gi_frame.f_yieldfrom != null) {
            PyObject ret = null;
            if (type == Py.GeneratorExit) {
                gi_running = true;
                try {
                    gen_close_iter(gi_frame.f_yieldfrom);
                } catch (PyException e) {
                    throw e;
                } finally {
                    gi_running = false;
                }
                return raiseException(type, value, tb);
            }
            if (gi_frame.f_yieldfrom instanceof PyGenerator) {
                gi_running = true;
                try {
                    ret = ((PyGenerator) gi_frame.f_yieldfrom).throw$(type, value, tb);
                } catch (Throwable e) {
                    throw e;
                } finally {
                    gi_running = false;
                }
            } else {
                PyObject meth = gi_frame.f_yieldfrom.__findattr__("close");
                if (meth != null) {
                    ret = meth.__call__();
                } else {
                    gi_frame.f_yieldfrom = null;
                    return raiseException(type, value, tb);
                }
            }
            if (ret == null) {
                gi_frame.f_yieldfrom = null;
                gi_frame.f_lasti++;
                ret = gen_send_ex(Py.getThreadState(), Py.None);
            }
            return ret;
        }
        return raiseException(type, value, tb);
    }

    public PyObject close() {
        return generator_close();
    }

    @ExposedMethod(doc = BuiltinDocs.generator_close_doc)
    final PyObject generator_close() {
        PyObject retval;
        PyObject yf = gi_frame.f_yieldfrom;
        if (yf != null) {
            gi_running = true;
            try {
                gi_frame.f_yieldfrom = null;
                gen_close_iter(yf);
            } catch (PyException e) {
                if (!e.match(Py.StopIteration) && !e.match(Py.GeneratorExit)) {
                    throw e;
                }
            } finally {
                gi_running = false;
            }
        }
        PyException pye = Py.GeneratorExit();
        try {
            // clean up
            retval = gen_send_ex(Py.getThreadState(), pye);
        } catch (PyException e) {
            if (e.match(Py.StopIteration) || e.match(Py.GeneratorExit)) {
                return Py.None;
            }
            throw e;
        }
        if (retval != null || retval != Py.None) {
            throw Py.RuntimeError("generator ignored GeneratorExit");
        }
        // not reachable
        return null;
    }

    @ExposedMethod(doc = BuiltinDocs.generator___next___doc)
    final PyObject generator___next__() {
        return gen_send_ex(Py.getThreadState(), Py.None);
    }

    @Override
    public PyObject __iter__() {
        return generator___iter__();
    }

    @ExposedMethod(doc = BuiltinDocs.generator___iter___doc)
    final PyObject generator___iter__() {
        return this;
    }

    private PyObject raiseException(PyObject type, PyObject value, PyObject tb) {
        PyException pye;
        if (value == null) {
            pye = PyException.doRaise(type);
        } else {
            pye = new PyException(type, value);
        }
        pye.traceback = (PyTraceback) tb;
        gi_frame.previousException = pye;
        return gen_send_ex(Py.getThreadState(), pye);
    }
    
    @Override
    public void __del_builtin__() {
        if (gi_frame == null || gi_frame.f_lasti == -1) {
            return;
        }
        try {
            close();
        } catch (PyException pye) {
            // PEP 342 specifies that if an exception is raised by close,
            // we output to stderr and then forget about it;
            String className =  PyException.exceptionClassName(pye.type);
            int lastDot = className.lastIndexOf('.');
            if (lastDot != -1) {
                className = className.substring(lastDot + 1);
            }
            String msg = String.format("Exception %s: %s in %s", className, pye.value.__repr__(),
                                       __repr__());
            Py.println(Py.getSystemState().stderr, Py.newString(msg));
        } catch (Throwable t) {
            // but we currently ignore any Java exception completely. perhaps we
            // can also output something meaningful too?
        }
    }

    @Override
    public PyObject __next__() {
        try {
            return gen_send_ex(Py.getThreadState(), Py.None);
        } catch (PyException e) {
            if (e.match(Py.StopIteration)) {
                return null;
            }
            throw e;
        }
    }

    @ExposedGet(name = "__name__")
    final String getName() {
        return gi_code.co_name;
    }

    @ExposedGet(name = "__qualname__")
    final String getQualname() {
        return gi_code.co_name;
    }

    @ExposedGet(name = "gi_yieldfrom")
    final PyObject getgi_yieldfrom() {
        return gi_frame.f_yieldfrom;
    }

    private PyObject gen_send_ex(ThreadState state, Object value) {
        if (gi_running) {
            throw Py.ValueError("generator already executing");
        }
        if (gi_frame == null) {
            throw Py.StopIteration();
        }
        if (gi_frame.previousException != null) {
            state.exceptions.offerFirst(gi_frame.previousException);
        }
        if (gi_frame.f_lasti == -1) {
            gi_frame = null;
            throw Py.StopIteration();
        }
        // if value is null, means the input is passed implicitly by frame, don't reset to None
        if (value != null && value != Py.None) {
            gi_frame.setGeneratorInput(value);
        }
        gi_running = true;
        PyObject result = null;
        try {
            result = gi_frame.f_code.call(state, gi_frame, closure);
        } catch (PyException pye) {
            gi_frame = null;
            throw pye;
        } finally {
            gi_running = false;
        }
        if (result == null && gi_frame.f_yieldfrom != null) {
            gi_frame.f_yieldfrom = null;
            gi_frame.f_lasti++;
            return gen_send_ex(state, value);
        }

        if (result == Py.None && gi_frame.f_lasti == -1) {
            gi_frame = null;
            throw Py.StopIteration();
        }
        return result;
    }

    private PyObject gen_close_iter(PyObject iter) {
        if (iter instanceof PyGenerator) {
            return ((PyGenerator) iter).close();
        }
        try {
            PyObject closeMeth = iter.__findattr_ex__("close");
            return closeMeth.__call__();
        } catch (PyException e) {
            if (! e.match(Py.AttributeError)) throw e;
            return null;
        }
    }

    /* Traverseproc implementation */
    @Override
    public int traverse(Visitproc visit, Object arg) {
        int retValue = super.traverse(visit, arg);
        if (retValue != 0) {
            return retValue;
        }
        if (gi_frame != null) {
            retValue = visit.visit(gi_frame, arg);
            if (retValue != 0) {
                return retValue;
            }
        }
        if (gi_code != null) {
            retValue = visit.visit(gi_code, arg);
            if (retValue != 0) {
                return retValue;
            }
        }
        return closure == null ? 0 : visit.visit(closure, arg);
    }

    @Override
    public boolean refersDirectlyTo(PyObject ob) {
        return ob != null && (ob == gi_frame || ob == gi_code
            || ob == closure || super.refersDirectlyTo(ob));
    }
}
