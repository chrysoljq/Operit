"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.onToolResultXmlRender = exports.onToolXmlRender = void 0;
exports.registerToolPkg = registerToolPkg;
const xmlRenderPlugin = require("./plugin/subagent-xml-render-plugin.js");
exports.onToolXmlRender = xmlRenderPlugin.onToolXmlRender;
exports.onToolResultXmlRender = xmlRenderPlugin.onToolResultXmlRender;
function registerToolPkg() {
    ToolPkg.registerXmlRenderPlugin({
        id: "subagent_tool_render",
        tag: "tool",
        function: xmlRenderPlugin.onToolXmlRender,
    });
    ToolPkg.registerXmlRenderPlugin({
        id: "subagent_tool_result_render",
        tag: "tool_result",
        function: xmlRenderPlugin.onToolResultXmlRender,
    });
    return true;
}
