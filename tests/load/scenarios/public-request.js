import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate } from 'k6/metrics';
import { randomUuid, requestHeaders, requireConfirmedTarget, runId, standardOptions, targetUrl } from '../lib/config.js';

const unexpectedStatus = new Rate('unexpected_status');
requireConfirmedTarget({ writes: true });
export const options = standardOptions('public-request');

export default function () {
  const policiesResponse = http.get(`${targetUrl}/api/v1/customer/consent-policies?context=REQUEST_SUBMISSION`, {
    headers: requestHeaders(),
    tags: { name: 'customer_request_policies' },
  });
  const formResponse = http.get(`${targetUrl}/api/v1/customer/ticket-forms`, {
    headers: requestHeaders(),
    tags: { name: 'customer_request_form' },
  });
  if (!check(policiesResponse, { 'request policies are available': (response) => response.status === 200 })) fail('policy projection failed');
  if (!check(formResponse, { 'request form is available': (response) => response.status === 200 })) fail('form projection failed');

  let form = formResponse.json();
  let fieldValues = requiredFieldValues(form);
  const projected = http.post(
    `${targetUrl}/api/v1/customer/ticket-form-projections`,
    JSON.stringify({ ticketKind: 'CUSTOMER_REQUEST', formId: form.formId, formVersion: form.formVersion, fieldValues }),
    {
      headers: requestHeaders({ 'Content-Type': 'application/json' }),
      tags: { name: 'customer_request_form_projection' },
    },
  );
  if (projected.status === 200) {
    form = projected.json();
    fieldValues = { ...fieldValues, ...requiredFieldValues(form) };
  }

  const policies = policiesResponse.json('policies') || [];
  const response = http.post(
    `${targetUrl}/api/v1/requests`,
    JSON.stringify({
      clientCommandId: randomUuid(),
      requester: {
        name: `Load test ${runId}`.slice(0, 200),
        email: `load-${runId}-${__VU}-${__ITER}@loadtest.invalid`.slice(0, 254),
      },
      subject: `Synthetic load request ${runId}`.slice(0, 200),
      message: 'Synthetic load-only public request. No production customer data.',
      formId: form.formId,
      formVersion: form.formVersion,
      fieldValues,
      acceptedPolicies: policies.filter((policy) => policy.required).map((policy) => ({
        policyKey: policy.policyKey,
        version: policy.version,
      })),
    }),
    {
      headers: requestHeaders({ 'Content-Type': 'application/json' }),
      tags: { name: 'public_request_create' },
    },
  );
  unexpectedStatus.add(response.status !== 201);
  check(response, { 'public request is created': (result) => result.status === 201 && result.json('ticketNumber') > 0 });
}

function requiredFieldValues(form) {
  const values = {};
  for (const projected of form.fields || []) {
    if (!projected.visible || !projected.editable || !projected.required) continue;
    const field = projected.field;
    if (field.type === 'CHECKBOX') values[field.machineKey] = { booleanValue: true };
    if (field.type === 'NUMBER') values[field.machineKey] = { numberValue: field.validation?.minimum ?? 1 };
    if (field.type === 'SINGLE_SELECT') {
      if (!projected.options?.length) fail(`required select ${field.machineKey} has no public option`);
      values[field.machineKey] = { optionId: projected.options[0].id };
    }
    if (field.type === 'SHORT_TEXT') values[field.machineKey] = { shortTextValue: 'loadtest' };
    if (field.type === 'LONG_TEXT') values[field.machineKey] = { longTextValue: 'Synthetic load test value' };
  }
  return values;
}
