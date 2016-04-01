'use strict';

angular.module('mountyhubApp')
    .factory('BonusMalusType', function ($resource, DateUtils) {
        return $resource('api/bonusMalusTypes/:id', {}, {
            'query': { method: 'GET', isArray: true},
            'get': {
                method: 'GET',
                transformResponse: function (data) {
                    data = angular.fromJson(data);
                    return data;
                }
            },
            'update': { method:'PUT' }
        });
    });
