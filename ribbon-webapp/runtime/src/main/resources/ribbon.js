var ribbon = (function() {

  var defaults = {
    headers: {},
    method: 'GET',
    keycloak: false,
    keycloakUpdateInterval: 30,
    context: '/ribbon'
  }, topology = {};

  function factory( options ) {
    options = merge(defaults, options);

    function ajax( serviceName, path, settings ) {
      var allServers = topology[serviceName],
          deferredResult = deferred(),
          keycloak = options.keycloak;

      if (!allServers || allServers.length < 1 ) {
        return deferredResult.reject('No servers available').promise;
      }

      // ensure some default settings exist
      settings = merge({
        method: options.method,
        headers: options.headers,
        data: undefined
      }, settings);

      path = path || '/';

      // TODO: Try other URLs if there is more than one server
      settings.url = '//' + allServers[0] + path;

      // Set relevant headers
      if (settings.method === 'POST') {
        settings.headers['Content-Type'] = 'application/json';
      }

      if (!keycloak) {
        // If we're not authenticating, go ahead and make the request
        return doRequest( settings ).promise;
      } else if (!keycloak.authenticated) {
        // But if we are authenticating...
        return deferredResult.reject("Not authenticated").promise;
      } else {
        // Using keycloak, update token, and make the request
        settings.headers.Authorization = 'Bearer ' + keycloak.token;

        // TODO: Make token update interval configurable
        keycloak.updateToken(30).success( function() {
          console.log( "ribbon.js: keycloak token refreshed" );

          // make the request
          doRequest( settings ).then( function(result) {
            deferredResult.resolve(result);
          });
        }).error( function(e) {
          console.log( "ribbon.js: Failed to update keycloak token. " + e );
          deferredResult.reject('Failed to update keycloak token. ' + e);
        });
      }

      return deferredResult.promise;
    }

    function doRequest(settings) {
      var request = new XMLHttpRequest(),
          deferredResponse = deferred();

      // set the state change handler and open the http request
      request.onreadystatechange = changeState(request, deferredResponse);
      request.open(settings.method, settings.url);

      getHeaders(settings.headers).forEach(function(header) {
        request.setRequestHeader(header.field, header.value);
      });

      if (settings.data) request.send(settings.data);
      else request.send();

      // return a deferred promise
      return deferredResponse;

      function changeState(request, deferredResponse) {
        return function() {
          switch(request.readyState) {
          case 0:
          case 1:
          case 2:
          case 3:
            break;
          case 4:
            processResponse(request, deferredResponse);
            break;
          default:
            console.log('ribbon.js: Unexpected XMLHttpRequest state');
            deferred.reject('Unexpected XMLHttpRequst state');
          }
        };
      }

      function processResponse(request, response) {
        if (request.status === 200) {
          response.resolve(JSON.parse(request.responseText));
        } else {
          response.reject('ribbon.js: Bad request: ' + request.statusText);
        }
      }

      function getHeaders(headers) {
        var headerList = [];
        for (var key in headers) {
          if (headers.hasOwnProperty(key)) {
            headerList.push({
              field: key,
              value: headers[key]
            });
          }
        }
        return headerList;
      }
    }

    function getJSON(serviceName, path) {
      return ajax( serviceName, path );
    }

    function postJSON(serviceName, path, data) {
      if (typeof path === 'object') {
        data = path;
        path = '/';
      }
      return ajax( serviceName, path, {
        method: 'POST',
        data: JSON.stringify(data)
      });
    }

    var sse = new EventSource( options.context + "/system/stream" );
    sse.addEventListener('topologyChange', function(message) {
      console.log('ribbon.js: topology changed: ', message.data);
      topology = JSON.parse(message.data);
    });

    sse.onerror = function(e) {
      console.log( "ribbon.js: topology SSE error", e );
    };

    function onTopologyChange(f) {
      sse.addEventListener('topologyChange', function(message) {
        f(message.data);
      });
    }

    var _ribbon = {
      ajax: ajax,
      postJSON: postJSON,
      getJSON: getJSON,
      onTopologyChange: onTopologyChange
    };

    Object.defineProperty(_ribbon, 'topology', {
      get: function() { return topology; },
      enumerable: true
    });
    Object.freeze(_ribbon);
    return _ribbon;
  }

  function merge(defaults, provided) {
    var obj = {};
    if (!provided) provided = {};
    for (var key in defaults) {
      if (defaults.hasOwnProperty(key))
        obj[key] = provided[key] || defaults[key];
    }
    return obj;
  }

  function promises() {
    var PENDING = 0,
        FULFILLED = 1,
        REJECTED = 2;

    function promise(fn) {

      var p = {
        state: PENDING,
        identify: 'fidelity',
        value: null,
        queue: [],
        handlers: {
          fulfill: null,
          reject: null
        }
      };
      p.then = then(p);

      if (isA('function', fn)) {
        fn(
          function(v) { resolve(p, v); },
          function(r) { reject(p, r); }
        );
      }

      return p;
    }

    function then(p) {
      return function(onFulfilled, onRejected) {
        var q = promise();
        if (isA('function', onFulfilled)) {
          q.handlers.fulfill = onFulfilled;
        }
        if (isA('function', onRejected)) {
          q.handlers.reject = onRejected;
        }
        p.queue.push(q);
        process(p);
        return q;
      };
    }

    function resolve(p, x) {
      if (x === p)
        reject(p, new TypeError('The promise and its value are the same.'));

      if (isPromise(x)) {
        if (x.state === PENDING) {
          x.then(function(value) {
            resolve(p, value);
          }, function(cause) {
            reject(p, cause);
          });
        } else {
          transition(p, x.state, x.value);
        }
      } else if (isA('function', x) || isA('object', x)) {
        var called = false, thenFunction;
        try {
          thenFunction = x.then;
          if (isA('function', thenFunction)) {
            thenFunction.call(x, function(y) {
              if (!called) {
                resolve(p, y);
                called = true;
              }
            }, function (r) {
              if (!called) {
                reject(p, r);
                called = true;
              }
            });
          } else {
            fulfill(p, x);
            called = true;
          }
        } catch (e) {
          if (!called) {
            reject(p, e);
            called = true;
          }
        }
      }
      else fulfill(p, x);
    }

    function fulfill(p, result) {
      transition(p, FULFILLED, result);
    }

    function reject(p, cause) {
      transition(p, REJECTED, cause);
    }

    function defaultFulfill(v) { return v; }

    function defaultReject(r) { throw r; }

    function process(p) {
      if (p.state === PENDING) return;
      setTimeout(function() {
        while(p.queue.length) {
          var qp = p.queue.shift(),
              handler, value;
          if (p.state === FULFILLED) {
            handler = qp.handlers.fulfill || defaultFulfill;
          } else if (p.state === REJECTED) {
            handler = qp.handlers.reject || defaultReject;
          }
          try {
            value = handler(p.value);
          } catch(e) {
            transition(qp, REJECTED, e);
            continue;
          }
          resolve(qp, value);
        }
      }, 0);
    }

    function transition(p, state, value) {
      if (p.state === state ||
          p.state !== PENDING ||
          arguments.length !== 3) return;
      p.state = state;
      p.value = value;
      process(p);
    }

    function isA(type, x) {
      return x && typeof x === type;
    }

    function isPromise(x) {
      return x && x.identify === 'fidelity';
    }

    promise.PENDING = PENDING;
    promise.FULFILLED = FULFILLED;
    promise.REJECTED = REJECTED;
    return promise;
  }

  var promise = promises(),
      deferred = function() {
        var resolver, rejecter,
            p = promise(function(resolve, reject) {
              resolver = resolve;
              rejecter = reject;
            });
        function resolve(value) {
          resolver(value);
        }
        function reject(cause) {
          rejecter(cause);
        }
        return {
          promise: p,
          resolve: resolve,
          reject: reject
        };
      };
  return factory;

})();
