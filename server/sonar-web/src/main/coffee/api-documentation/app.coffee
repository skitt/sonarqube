requirejs.config
  baseUrl: "#{baseUrl}/js"

  paths:
    'jquery': 'third-party/jquery'
    'backbone': 'third-party/backbone'
    'backbone.marionette': 'third-party/backbone.marionette'
    'handlebars': 'third-party/handlebars'
    'moment': 'third-party/moment'

  shim:
    'backbone.marionette':
      deps: ['backbone']
      exports: 'Marionette'
    'backbone':
      exports: 'Backbone'
    'handlebars':
      exports: 'Handlebars'
    'moment':
      exports: 'moment'


requirejs [
  'backbone', 'backbone.marionette', 'handlebars',
  'api-documentation/collections/web-services',
  'api-documentation/views/api-documentation-list-view',
  'api-documentation/router',
  'api-documentation/layout',
  'common/handlebars-extensions'
], (
  Backbone, Marionette, Handlebars,
  WebServices,
  ApiDocumentationListView,
  ApiDocumentationRouter,
  ApiDocumentationLayout
) ->

  # Create a Quality Gate Application
  App = new Marionette.Application

  App.webServices = new WebServices

  App.openFirstWebService = ->
    if @webServices.length > 0
      @router.navigate "#{@webServices.models[0].get('path')}", trigger: true
    else
      App.layout.detailsRegion.reset()

  App.refresh = ->
    App.apiDocumentationListView = new ApiDocumentationListView
      collection: App.webServices
      app: App
    App.layout.resultsRegion.show App.apiDocumentationListView
    if (Backbone.history.fragment)
      App.router.show Backbone.history.fragment, trigger: true

  # Construct layout
  App.addInitializer ->
    @layout = new ApiDocumentationLayout app: App
    jQuery('#body').append @layout.render().el

  # Construct sidebar
  App.addInitializer ->
    App.refresh()

  # Start router
  App.addInitializer ->
    @router = new ApiDocumentationRouter app: @
    Backbone.history.start()

  # Open first Web Service when page is opened
  App.addInitializer ->
    initial = Backbone.history.fragment == ''
    App.openFirstWebService() if initial

  webServicesXHR = App.webServices.fetch()

  jQuery.when(webServicesXHR)
    .done ->
      # Remove the initial spinner
      jQuery('#api-documentation-page-loader').remove()

      # Start the application
      App.start()
