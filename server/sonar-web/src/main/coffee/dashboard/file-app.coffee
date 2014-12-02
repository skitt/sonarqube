requirejs.config
  baseUrl: "#{baseUrl}/js"

  paths:
    'backbone': 'third-party/backbone'
    'backbone.marionette': 'third-party/backbone.marionette'
    'handlebars': 'third-party/handlebars'

  shim:
    'backbone.marionette':
      deps: ['backbone']
      exports: 'Marionette'
    'backbone':
      exports: 'Backbone'
    'handlebars':
      exports: 'Handlebars'


requirejs [
  'backbone.marionette'
  'source-viewer/viewer'
  'common/handlebars-extensions'
], (
  Marionette
  ComponentViewer
) ->

  $ = jQuery

  App = new Marionette.Application()
  el = $('#accordion-panel')


  App.addRegions
    viewerRegion: '#accordion-panel'


  App.requestComponentViewer = ->
    unless App.componentViewer?
      App.componentViewer = new ComponentViewer()
      App.viewerRegion.show App.componentViewer
    App.componentViewer


  App.addInitializer ->
    viewer = App.requestComponentViewer()
    viewer.open window.file.uuid, window.file.key


  # Message bundles
  l10nXHR = window.requestMessages()


  $.when(l10nXHR).done ->
    # Start the application
    App.start()
