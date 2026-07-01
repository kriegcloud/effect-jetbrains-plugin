(function installEffectDevToolsInstrumentation(globalObject) {
  "use strict";

  var instrumentationKey = "effect/devtools/instrumentation";
  var trackedFiberKey = "effect/instrumentation/tracked";
  var defectObserverKey = "effect/instrumentation/defectObserver";
  var firstSeenKey = "effect/instrumentation/firstSeenMillis";

  // Effect v4 (effect-smol) publishes the current fiber on globalThis under this fixed, non
  // version-tagged key (packages/effect/src/Redactable.ts). Effect v3 used "effect/FiberCurrent".
  // We watch both so a single instrumentation build works against either runtime.
  var currentFiberKeyV4 = "~effect/Fiber/currentFiber";
  var currentFiberKeyV3 = "effect/FiberCurrent";

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
  var NONE = { found: false };

  function isObject(value) {
    return typeof value === "object" && value !== null;
  }

  function nowMillis() {
    return typeof Date !== "undefined" && typeof Date.now === "function" ? Date.now() : 0;
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

  // The current fiber lives under the v4 key on modern runtimes and the v3 key otherwise. During
  // asynchronous suspension both are restored to undefined, so callers should treat a falsy result
  // as "no synchronously-current fiber".
  function getCurrentFiber() {
    return globalObject[currentFiberKeyV4] || globalObject[currentFiberKeyV3];
  }

  // v4 Option is read structurally via `valueOrUndefined`; v3 Options exposed `{ _tag: "Some", value }`.
  function optionValueOrUndefined(option) {
    if (!isObject(option)) {
      return undefined;
    }
    if ("valueOrUndefined" in option) {
      return option.valueOrUndefined;
    }
    return option._tag === "Some" ? option.value : undefined;
  }

  function isObjectWithTag(value, tag) {
    return isObject(value) && value._tag === tag;
  }

  // v4 Cause is a flat `reasons: Array<Fail | Die | Interrupt>` (Cause.ts). v3 Cause was a recursive
  // Empty/Sequential/Parallel tree. Return { found, defect } for the first Die reason under either shape.
  function firstDieDefect(cause) {
    if (!isObject(cause)) {
      return NONE;
    }
    var reasons = cause.reasons;
    if (Array.isArray(reasons)) {
      for (var i = 0; i < reasons.length; i++) {
        var reason = reasons[i];
        if (isObject(reason) && reason._tag === "Die" && "defect" in reason) {
          return { found: true, defect: reason.defect };
        }
      }
      return NONE;
    }
    // Legacy v3 recursive cause tree.
    var stack = [cause];
    while (stack.length > 0) {
      var item = stack.pop();
      if (!isObject(item) || !("_tag" in item)) {
        continue;
      }
      if (item._tag === "Die" && "defect" in item) {
        return { found: true, defect: item.defect };
      }
      if (item._tag === "Parallel" || item._tag === "Sequential") {
        stack.push(item.right);
        stack.push(item.left);
      }
    }
    return NONE;
  }

  function exitDieDefect(exit) {
    if (!isObject(exit) || exit._tag !== "Failure" || !("cause" in exit)) {
      return NONE;
    }
    return firstDieDefect(exit.cause);
  }

  // v4 exposes `fiber.id` as a monotonic number property (internal/effect.ts). v3 exposed `fiber.id()`
  // returning a struct with `.id`. Accept both.
  function encodeFiberId(fiber) {
    try {
      if (!fiber) {
        return "";
      }
      var id = typeof fiber.id === "function" ? fiber.id() : fiber.id;
      if (isObject(id)) {
        id = id.id;
      }
      return id === undefined || id === null ? "" : String(id);
    } catch (_) {
      return "";
    }
  }

  // v4: `fiber._children` (Set) populated for non-daemon children; `fiber.children()` lazily creates it.
  // v3: `fiber.getChildren()` / `fiber._children`.
  function fiberChildren(fiber) {
    if (!fiber) {
      return [];
    }
    var children = fiber._children;
    if (!children && typeof fiber.getChildren === "function") {
      try { children = fiber.getChildren(); } catch (_) { children = undefined; }
    }
    if (!children && typeof fiber.children === "function") {
      try { children = fiber.children(); } catch (_) { children = undefined; }
    }
    if (!children) {
      return [];
    }
    try {
      return Array.from(children);
    } catch (_) {
      return [];
    }
  }

  function fiberIsInterruptible(fiber) {
    // v4: direct boolean. v3: interruptibility encoded in `currentRuntimeFlags` bit math.
    if (typeof fiber.interruptible === "boolean") {
      return fiber.interruptible;
    }
    if ("currentRuntimeFlags" in fiber) {
      var flags = fiber.currentRuntimeFlags;
      var interruption = 1 << 0;
      var windDown = 1 << 4;
      return (flags & interruption) !== 0 && (flags & windDown) === 0;
    }
    return false;
  }

  function fiberIsInterrupted(fiber) {
    // v4: `_interruptedCause` set once interruption begins. v3: `isInterrupted()` method.
    if ("_interruptedCause" in fiber) {
      return fiber._interruptedCause !== undefined && fiber._interruptedCause !== null;
    }
    return typeof fiber.isInterrupted === "function" && fiber.isInterrupted();
  }

  function encodeStackLocation(path, line, column) {
    return {
      path: String(path),
      line: Number(line),
      column: Number(column)
    };
  }

  // Parse a single cleaned stack line ("at name (path:line:col)" or "path:line:col") into a 0-based
  // location. v4 StackFrame.stack() returns such a cleaned line (internal/tracer.ts).
  function parseStackLine(text) {
    if (!text) {
      return null;
    }
    var line = String(text).split("\n").map(function(part) { return part.trim(); }).filter(Boolean)[0] || "";
    var match = line.match(/^at (.*) \((.*):(\d+):(\d+)\)$/) || line.match(/^at (.*):(\d+):(\d+)$/) || line.match(/([^()\s]+):(\d+):(\d+)\)?$/);
    if (!match) {
      return null;
    }
    if (match.length === 5) {
      return encodeStackLocation(match[2], parseInt(match[3], 10) - 1, parseInt(match[4], 10) - 1);
    }
    return encodeStackLocation(match[1], parseInt(match[2], 10) - 1, parseInt(match[3], 10) - 1);
  }

  function stackFrameLocation(frame) {
    if (!frame || typeof frame.stack !== "function") {
      return null;
    }
    try {
      return parseStackLine(frame.stack());
    } catch (_) {
      return null;
    }
  }

  // Build name -> source-location and an ordered list by walking the fiber's StackFrame chain
  // (v4: `fiber.currentStackFrame`, each frame `{ name, stack(), parent }`). v3 stored span->stack in a
  // global store which no longer exists in v4.
  function fiberStackFrameLocations(fiber) {
    var byName = {};
    var ordered = [];
    var frame = fiber && fiber.currentStackFrame;
    var guard = 0;
    while (frame && guard < 256) {
      var location = stackFrameLocation(frame);
      ordered.push(location || null);
      if (location && frame.name && !(frame.name in byName)) {
        byName[frame.name] = location;
      }
      frame = frame.parent;
      guard++;
    }
    return { byName: byName, ordered: ordered };
  }

  function spanAttributes(span) {
    if (!span || span._tag !== "Span" || !span.attributes) {
      return [];
    }
    if (typeof span.attributes.entries === "function") {
      try { return Array.from(span.attributes.entries()); } catch (_) { return []; }
    }
    if (isObject(span.attributes)) {
      return Object.keys(span.attributes).map(function(key) { return [key, span.attributes[key]]; });
    }
    return [];
  }

  function getFiberCurrentSpanStack(fiber, maxDepth) {
    var spans = [];
    var current = fiber && fiber.currentSpan;
    var frames = fiberStackFrameLocations(fiber);
    var depth = 0;
    while (current) {
      if (maxDepth !== 0 && depth >= maxDepth) {
        break;
      }
      var isSpan = current._tag === "Span";
      var name = isSpan ? String(current.name || "") : String(current.spanId || "");
      var location = frames.byName[name] || frames.ordered[depth] || null;
      spans.push({
        _tag: current._tag,
        spanId: String(current.spanId || ""),
        traceId: String(current.traceId || ""),
        name: name,
        attributes: spanAttributes(current),
        stack: location ? [location] : []
      });
      current = isSpan ? optionValueOrUndefined(current.parent) : undefined;
      depth++;
    }
    return spans;
  }

  // v4: a fiber's whole Context lives in `fiber.context.mapUnsafe` (string service key -> value).
  // v3: `fiber._fiberRefs.locals` then `context.unsafeMap.entries()`.
  function getFiberCurrentContext(fiber) {
    if (!fiber) {
      return [];
    }
    var context = fiber.context;
    var map = context && (context.mapUnsafe || context.unsafeMap);
    if (map && typeof map.entries === "function") {
      try { return Array.from(map.entries()); } catch (_) { return []; }
    }
    // Legacy v3 fiber refs fallback.
    if (fiber._fiberRefs && fiber._fiberRefs.locals && typeof fiber._fiberRefs.locals.values === "function") {
      return Array.from(fiber._fiberRefs.locals.values()).map(function(entry) {
        return entry && entry[0] ? entry[0][1] : undefined;
      }).filter(function(value) {
        return isObject(value) && (value.unsafeMap || value.mapUnsafe);
      }).flatMap(function(context) {
        var legacyMap = context.mapUnsafe || context.unsafeMap;
        return legacyMap && typeof legacyMap.entries === "function" ? Array.from(legacyMap.entries()) : [];
      });
    }
    return [];
  }

  function getAliveFibers() {
    var current = getCurrentFiber();
    return fibers.map(function(fiber) {
      var firstSeen = Number(fiber[firstSeenKey] || 0);
      return {
        id: encodeFiberId(fiber),
        isCurrent: fiber === current,
        isInterruptible: fiberIsInterruptible(fiber),
        isInterrupted: fiberIsInterrupted(fiber),
        children: fiberChildren(fiber).map(encodeFiberId),
        startTimeMillis: firstSeen,
        lifeTimeMillis: firstSeen ? nowMillis() - firstSeen : 0
      };
    });
  }

  function interruptFiber(fiberId) {
    fibers.forEach(function(fiber) {
      if (encodeFiberId(fiber) !== String(fiberId)) {
        return;
      }
      if (typeof fiber.interruptUnsafe === "function") {
        fiber.interruptUnsafe();
      } else if (typeof fiber.unsafeInterruptAsFork === "function") {
        fiber.unsafeInterruptAsFork(typeof fiber.id === "function" ? fiber.id() : fiber.id);
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
    return getFiberCurrentContext(fiber || getCurrentFiber()).map(function(entry) {
      var key = entry && entry[0];
      var value = entry && entry[1];
      var tag;
      if (typeof key === "string") {
        tag = key;
      } else if (key && key.key) {
        tag = String(key.key);
      } else {
        tag = snapshotValue(key, 0).summary;
      }
      return {
        tag: tag,
        value: snapshotValue(value, 2)
      };
    });
  }

  function getFiberCurrentSpanStackSnapshot(fiber, maxDepth) {
    return getFiberCurrentSpanStack(fiber || getCurrentFiber(), maxDepth || 64).map(function(span, index) {
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

  // v4 pure global-injection cannot break synchronously at the throw site (the run loop converts
  // throws to Exit.Die internally), so we detect a Die defect when the fiber completes and surface it
  // to the Breakpoints panel. This is best-effort, matching the "adapted" debugger parity note.
  function installDefectObserver(fiber) {
    if (!fiber || fiber[defectObserverKey] || typeof fiber.addObserver !== "function") {
      return;
    }
    fiber[defectObserverKey] = true;
    try {
      fiber.addObserver(function(exit) {
        if (!debuggerState.pauseOnDefects) {
          return;
        }
        var die = exitDieDefect(exit);
        if (!die.found) {
          return;
        }
        var defect = die.defect;
        if (debuggerState.lastDefect && debuggerState.lastDefect.value === defect) {
          return;
        }
        debuggerState.lastDefect = { value: defect };
        debuggerState.valuesToReveal = [{ label: "Fiber Defect", value: snapshotValue(defect, 2) }];
        debuggerState.locationToReveal = fiber.currentStackFrame ? stackFrameLocation(fiber.currentStackFrame) : null;
        debugger;
      });
    } catch (_) {
      // Ignore observer registration failures on already-completed fibers.
    }
  }

  function addTrackedFiber(fiber) {
    if (!fiber || fiber[trackedFiberKey]) {
      return;
    }
    try { fiber[trackedFiberKey] = true; } catch (_) { return; }
    try { fiber[firstSeenKey] = nowMillis(); } catch (_) { /* frozen fiber */ }
    fibers.push(fiber);
    installDefectObserver(fiber);
    fiberChildren(fiber).forEach(addTrackedFiber);
    if (typeof fiber.addObserver === "function") {
      try {
        fiber.addObserver(function() {
          var index = fibers.indexOf(fiber);
          if (index >= 0) {
            fibers.splice(index, 1);
          }
        });
      } catch (_) {
        // Already-completed fibers report immediately; nothing to remove.
      }
    }
  }

  globalObject[instrumentationKey] = {
    fibers: fibers,
    getCurrentFiber: getCurrentFiber,
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

  // Watch the current-fiber global under both the v4 and v3 keys so newly-scheduled fibers are tracked
  // as soon as they run. Values are restored to undefined at async boundaries, hence the set trap.
  [currentFiberKeyV4, currentFiberKeyV3].forEach(function(key) {
    var previous = globalObject[key];
    addSetInterceptor(globalObject, key, function(fiber) {
      addTrackedFiber(fiber);
    });
    globalObject[key] = previous;
  });

  var initialFiber = getCurrentFiber();
  if (initialFiber) {
    addTrackedFiber(initialFiber);
  }
})(typeof globalThis === "undefined" ? this : globalThis);
