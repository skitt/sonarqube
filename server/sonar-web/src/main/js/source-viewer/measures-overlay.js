define([
  'common/overlay',
  'templates/source-viewer'
], function (Overlay, Templates) {

  var $ = jQuery,
      SOURCE_METRIC_LIST = [
        'accessors',
        'classes',
        'functions',
        'statements',
        'ncloc',
        'lines',
        'generated_ncloc',
        'generated_lines',
        'complexity',
        'function_complexity',
        'comment_lines',
        'comment_lines_density',
        'public_api',
        'public_undocumented_api',
        'public_documented_api_density'
      ],
      COVERAGE_METRIC_LIST = [
        'coverage',
        'line_coverage',
        'lines_to_cover',
        'uncovered_lines',
        'branch_coverage',
        'conditions_to_cover',
        'uncovered_conditions',
        'it_coverage',
        'it_line_coverage',
        'it_lines_to_cover',
        'it_uncovered_lines',
        'it_branch_coverage',
        'it_conditions_to_cover',
        'it_uncovered_conditions',
        'overall_coverage',
        'overall_line_coverage',
        'overall_lines_to_cover',
        'overall_uncovered_lines',
        'overall_branch_coverage',
        'overall_conditions_to_cover',
        'overall_uncovered_conditions'
      ],
      ISSUES_METRIC_LIST = [
        'blocker_violations',
        'critical_violations',
        'major_violations',
        'minor_violations',
        'info_violations'
      ],
      DUPLICATIONS_METRIC_LIST = [
        'duplicated_lines_density',
        'duplicated_blocks',
        'duplicated_lines'
      ],

      TESTS_METRIC_LIST = [
        'tests',
        'test_success_density',
        'test_failures',
        'test_errors',
        'skipped_tests',
        'test_execution_time'
      ];


  return Overlay.extend({
    template: Templates['source-viewer-measures'],

    onRender: function () {
      Overlay.prototype.onRender.apply(this, arguments);
      this.$('.js-pie-chart').pieChart();
    },

    show: function () {
      var that = this;
      this.requestMeasures().done(function () {
        that.render();
      });
    },

    requestMeasures: function () {
      var that = this,
          p = window.process.addBackgroundProcess(),
          url = baseUrl + '/api/resources',
          options = {
            resource: this.model.key(),
            metrics: [].concat(
                SOURCE_METRIC_LIST,
                COVERAGE_METRIC_LIST,
                ISSUES_METRIC_LIST,
                DUPLICATIONS_METRIC_LIST,
                TESTS_METRIC_LIST
            ).join()
          };
      return $.get(url, options).done(function (data) {
        var measuresList = data[0].msr || [],
            measures = that.model.get('measures') || {};
        measuresList.forEach(function (m) {
          measures[m.key] = m.frmt_val || m.data;
          measures[m.key + '_raw'] = m.val;
        });
        that.model.set({ measures: measures });
        window.process.finishBackgroundProcess(p);
      }).fail(function () {
        window.process.failBackgroundProcess(p);
      });
    }
  });

});
