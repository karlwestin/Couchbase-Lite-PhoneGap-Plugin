module.exports = {
    getURL : function(callback) {
         // use node.js style error reporting (first argument)
         cordova.exec(function(url){
            callback(false, url);
         }, function(err) {
            callback(err);
        }, "CBLite", "getURL", []);
    },
    replicate : function(local, remote, callback) {
         // use node.js style error reporting (first argument)
         cordova.exec(function(url){
            callback(false, url);
         }, function(err) {
            callback(err);
        }, "CBLite", "replicate", [local, remote]);
    },
    listen: function(dbName, callback) {
        cordova.exec(function(url){
          callback(false, url);
        }, function(err) {
          callback(err);
      }, "CBLite", "listen", [dbName]);
    }
};
