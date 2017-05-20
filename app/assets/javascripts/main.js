/**
 * Require JS main configuration
 */
(function (requirejs) {
    "use strict";

    requirejs.config({
        shim: {
            "ko.sortable": ["knockout", "jquery.ui.sortable"]
        },
        paths: {
            requirejs: ["../lib/requirejs/require"],
            jquery: ["../lib/jquery/jquery"],
            "jquery-ui": ["../lib/jquery-ui/jquery-ui.min"],
            "jquery.ui.sortable": ["../lib/jquery-ui/jquery-ui"],
            knockout: ["../lib/knockout/knockout"],
            "ko.sortable": ["knockout-sortable.min"],
            bootstrap: ["../lib/bootstrap/js/bootstrap"],
            sammy: ["../lib/sammy/sammy"],
            moment: ["../lib/momentjs/moment"],
            jsroutes: ["/jsroutes"]
        }
    });

    return requirejs;
})(requirejs);
