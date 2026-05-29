(function installEffectDevToolsInstrumentation(globalObject) {
  "use strict";

  var instrumentationKey = "effect/devtools/instrumentation";
  var currentInstrumentationTracerKey = "effect/instrumentation/currentTracer";
  var contextTypeId = typeof Symbol === "function" ? Symbol.for("effect/Context") : "effect/Context";
  var effectTypeId = typeof Symbol === "function" ? Symbol.for("effect/Effect") : "effect/Effect";
  var originalAnnotation = typeof Symbol === "function" ? Symbol.for("effect/OriginalAnnotation") : "effect/OriginalAnnotation";

  if (!globalObject || globalObject[instrumentationKey]) {
    return;
  }

  var fibers = [];
  var debuggerState = {
    pauseOnDefects: false,
    lastDefect: null,
    locationToReveal: null,
    valuesToReveal: []
  };

  function isObject(value) {
    return typeof value === "object" && value !== null;
  }

  function addSetInterceptor(obj, key, onSet) {
    var descriptor = Object.getOwnPropertyDescriptor(obj, key);
    var value = descriptor && "value" in descriptor ? descriptor.value : obj[key];

    Object.defineProperty(obj, key, {
      configurable: true,
      enumerable: descriptor ? descriptor.enumerable : true,
      get: descriptor && descriptor.get ? function() {
        return descriptor.get.call(this);
      } : function() {
        return value;
      },
      set: function(next) {
        value = next;
        onSet(next);
        if (descriptor && descriptor.set) {
          descriptor.set.call(this, next);
        }
      }
    });
  }

  function optionValue(option) {
    return option && option._tag === "Some" ? option.value : undefined;
  }

  function originalInstance(value) {
    return isObject(value) && originalAnnotation in value ? value[originalAnnotation] : value;
  }

  function causeDieOption(self) {
    var stack = [self];
    while (stack.length > 0) {
      var item = stack.pop();
      if (!isObject(item) || !("_tag" in item)) {
        continue;
      }
      if (item._tag === "Die" && "defect" in item) {
        return { _tag: "Some", value: item.defect };
      }
      if (item._tag === "Parallel" || item._tag === "Sequential") {
        stack.push(item.right);
        stack.push(item.left);
      }
    }
    return { _tag: "None" };
  }

  function isExitFailure(value) {
    return isObject(value) && effectTypeId in value && value._tag === "Failure" && "cause" in value;
  }

  function interruptible(flags) {
    var interruption = 1 << 0;
    var windDown = 1 << 4;
    return (flags & interruption) !== 0 && (flags & windDown) === 0;
  }

  function globalStores() {
    return Object.keys(globalObject).filter(function(key) {
      return key.indexOf("effect/GlobalValue/globalStoreId") > -1 || key === "effect/GlobalValue";
    }).map(function(key) {
      return globalObject[key];
    });
  }

  function encodeFiberId(fiber) {
    try {
      return fiber && typeof fiber.id === "function" && fiber.id() ? String(fiber.id().id) : "";
    } catch (_) {
      return "";
    }
  }

  function encodeStackLocation(path, line, column) {
    return {
      path: path,
      line: Number(line),
      column: Number(column)
    };
  }

  function getSpanStack(span) {
    var stackString = globalStores().reduce(function(acc, store) {
      if (acc || !store || typeof store.get !== "function") {
        return acc;
      }
      var spanToTrace = store.get("effect/Tracer/spanToTrace");
      var stackFn = spanToTrace && typeof spanToTrace.get === "function" ? spanToTrace.get(span) : undefined;
      return typeof stackFn === "function" ? stackFn() : acc;
    }, undefined) || "";

    return String(stackString).split("\n").filter(Boolean).map(function(line) {
      var match = line.match(/^at (.*) \((.*):(\d+):(\d+)\)$/) || line.match(/^at (.*):(\d+):(\d+)$/);
      if (!match) {
        return null;
      }
      if (match.length === 5) {
        return encodeStackLocation(match[2], parseInt(match[3], 10) - 1, parseInt(match[4], 10) - 1);
      }
      return encodeStackLocation(match[1], parseInt(match[2], 10) - 1, parseInt(match[3], 10) - 1);
    }).filter(Boolean);
  }

  function getFiberCurrentSpanStack(fiber, maxDepth) {
    var spans = [];
    var current = fiber && fiber.currentSpan;
    var depth = 0;
    while (current) {
      if (maxDepth !== 0 && depth >= maxDepth) {
        break;
      }
      spans.push({
        _tag: current._tag,
        spanId: String(current.spanId || ""),
        traceId: String(current.traceId || ""),
        name: current._tag === "Span" ? String(current.name || "") : String(current.spanId || ""),
        attributes: current._tag === "Span" && current.attributes && typeof current.attributes.entries === "function"
          ? Array.from(current.attributes.entries())
          : [],
        stack: getSpanStack(current)
      });
      current = current._tag === "Span" ? optionValue(current.parent) : undefined;
      depth++;
    }
    return spans;
  }

  function getFiberCurrentContext(fiber) {
    if (!fiber || !fiber._fiberRefs || !fiber._fiberRefs.locals || typeof fiber._fiberRefs.locals.values !== "function") {
      return [];
    }

    return Array.from(fiber._fiberRefs.locals.values()).map(function(entry) {
      return entry && entry[0] ? entry[0][1] : undefined;
    }).filter(function(value) {
      return isObject(value) && contextTypeId in value;
    }).flatMap(function(context) {
      return context.unsafeMap && typeof context.unsafeMap.entries === "function"
        ? Array.from(context.unsafeMap.entries())
        : [];
    });
  }

  function getAliveFibers() {
    var currentFiber = globalObject["effect/FiberCurrent"];
    return fibers.map(function(fiber) {
      var id = typeof fiber.id === "function" ? fiber.id() : {};
      return {
        id: encodeFiberId(fiber),
        isCurrent: fiber === currentFiber,
        isInterruptible: "currentRuntimeFlags" in fiber && interruptible(fiber.currentRuntimeFlags),
        isInterrupted: typeof fiber.isInterrupted === "function" && fiber.isInterrupted(),
        children: typeof fiber.getChildren === "function" ? Array.from(fiber.getChildren()).map(encodeFiberId) : [],
        startTimeMillis: Number(id.startTimeMillis || 0),
        lifeTimeMillis: id.startTimeMillis ? Date.now() - Number(id.startTimeMillis) : 0
      };
    });
  }

  function interruptFiber(fiberId) {
    fibers.forEach(function(fiber) {
      if (encodeFiberId(fiber) === String(fiberId) && typeof fiber.unsafeInterruptAsFork === "function") {
        fiber.unsafeInterruptAsFork(fiber.id());
      }
    });
  }

  function getAutoPauseConfig() {
    return {
      pauseOnDefects: debuggerState.pauseOnDefects
    };
  }

  function togglePauseOnDefects() {
    debuggerState.pauseOnDefects = !debuggerState.pauseOnDefects;
    debuggerState.lastDefect = null;
    return getAutoPauseConfig();
  }

  function getAndUnsetPauseStateToReveal() {
    var out = {
      location: debuggerState.locationToReveal,
      values: debuggerState.valuesToReveal
    };
    debuggerState.locationToReveal = null;
    debuggerState.valuesToReveal = [];
    return out;
  }

  function snapshotValue(value, depth, seen) {
    seen = seen || [];
    if (value === null) {
      return { label: "null", type: "null", summary: "null", children: [] };
    }
    if (value === undefined) {
      return { label: "undefined", type: "undefined", summary: "undefined", children: [] };
    }
    var type = typeof value;
    if (type !== "object" && type !== "function") {
      return { label: String(value), type: type, summary: String(value), children: [] };
    }
    if (seen.indexOf(value) >= 0) {
      return { label: "[Circular]", type: type, summary: "[Circular]", children: [] };
    }

    var nextSeen = seen.concat([value]);
    var label = value && value._tag ? String(value._tag) : (value && value.constructor && value.constructor.name ? value.constructor.name : type);
    var keys = [];
    try {
      keys = Object.keys(value).slice(0, 24);
    } catch (_) {
      keys = [];
    }

    return {
      label: label,
      type: label,
      summary: keys.length > 0 ? label + " {" + keys.slice(0, 5).join(", ") + (keys.length > 5 ? ", ..." : "") + "}" : label,
      children: depth > 0 ? keys.map(function(key) {
        return Object.assign({ label: key }, snapshotValue(value[key], depth - 1, nextSeen));
      }) : []
    };
  }

  function getFiberCurrentContextSnapshot(fiber) {
    return getFiberCurrentContext(fiber || globalObject["effect/FiberCurrent"]).map(function(entry) {
      var tag = entry && entry[0];
      var value = entry && entry[1];
      return {
        tag: tag && tag.key ? String(tag.key) : snapshotValue(tag, 0).summary,
        value: snapshotValue(value, 2)
      };
    });
  }

  function getFiberCurrentSpanStackSnapshot(fiber, maxDepth) {
    return getFiberCurrentSpanStack(fiber || globalObject["effect/FiberCurrent"], maxDepth || 64).map(function(span, index) {
      var firstStack = span.stack && span.stack[0];
      return {
        name: span.name,
        traceId: span.traceId,
        spanId: span.spanId,
        stackIndex: index,
        path: firstStack ? firstStack.path : null,
        line: firstStack ? firstStack.line : null,
        column: firstStack ? firstStack.column : null,
        attributes: span.attributes || []
      };
    });
  }

  function getAliveFibersSnapshot() {
    return getAliveFibers().map(function(fiber) {
      var trackedFiber = fibers.filter(function(candidate) {
        return encodeFiberId(candidate) === fiber.id;
      })[0];
      return Object.assign({}, fiber, {
        stack: getFiberCurrentSpanStackSnapshot(trackedFiber, 8)
      });
    });
  }

  function addTracerInterceptorToFiber(fiber) {
    if (!fiber || fiber[currentInstrumentationTracerKey]) {
      return;
    }
    fiber[currentInstrumentationTracerKey] = true;

    var previousTracer = fiber.currentTracer;
    addSetInterceptor(fiber, "currentTracer", function(tracer) {
      if (!tracer || tracer[currentInstrumentationTracerKey]) {
        return;
      }
      tracer[currentInstrumentationTracerKey] = true;
      if (typeof tracer.context !== "function") {
        return;
      }
      var originalContext = tracer.context.bind(tracer);
      tracer.context = function(f, currentFiber) {
        var args = Array.prototype.slice.call(arguments, 2);
        var result = originalContext.apply(undefined, [f, currentFiber].concat(args));
        if (debuggerState.pauseOnDefects && isExitFailure(result)) {
          var maybeDefect = causeDieOption(result.cause);
          if (maybeDefect._tag === "Some") {
            var defect = originalInstance(maybeDefect.value);
            if (!debuggerState.lastDefect || debuggerState.lastDefect.value !== defect || debuggerState.lastDefect.span !== currentFiber.currentSpan) {
              debuggerState.lastDefect = { value: defect, span: currentFiber.currentSpan };
              debuggerState.valuesToReveal = [{ label: "Fiber Defect", value: snapshotValue(defect, 2) }];
              var stack = currentFiber.currentSpan ? getSpanStack(currentFiber.currentSpan) : [];
              debuggerState.locationToReveal = stack[0] || null;
              debugger;
            }
          }
        }
        return result;
      };
    });
    fiber.currentTracer = previousTracer;
  }

  function addTrackedFiber(fiber) {
    if (!fiber || fibers.indexOf(fiber) >= 0) {
      return;
    }
    addTracerInterceptorToFiber(fiber);
    fibers.push(fiber);
    if (fiber._children && typeof fiber._children.forEach === "function") {
      fiber._children.forEach(addTrackedFiber);
    }
    if (typeof fiber.addObserver === "function") {
      fiber.addObserver(function() {
        var index = fibers.indexOf(fiber);
        if (index >= 0) {
          fibers.splice(index, 1);
        }
      });
    }
  }

  globalObject[instrumentationKey] = {
    fibers: fibers,
    getFiberCurrentContext: getFiberCurrentContext,
    getFiberCurrentSpanStack: getFiberCurrentSpanStack,
    getAliveFibers: getAliveFibers,
    interruptFiber: interruptFiber,
    getAutoPauseConfig: getAutoPauseConfig,
    togglePauseOnDefects: togglePauseOnDefects,
    getAndUnsetPauseStateToReveal: getAndUnsetPauseStateToReveal,
    getFiberCurrentContextSnapshot: getFiberCurrentContextSnapshot,
    getFiberCurrentSpanStackSnapshot: getFiberCurrentSpanStackSnapshot,
    getAliveFibersSnapshot: getAliveFibersSnapshot,
    getBreakpointSnapshot: function() {
      return Object.assign({ pauseOnDefects: debuggerState.pauseOnDefects }, getAndUnsetPauseStateToReveal());
    }
  };

  var previousFiber = globalObject["effect/FiberCurrent"];
  addSetInterceptor(globalObject, "effect/FiberCurrent", function(fiber) {
    addTrackedFiber(fiber);
  });
  globalObject["effect/FiberCurrent"] = previousFiber;
})(typeof globalThis === "undefined" ? this : globalThis);
