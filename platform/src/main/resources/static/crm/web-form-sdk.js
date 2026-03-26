/**
 * AuraBoot Web Form SDK
 *
 * Embed a CRM lead capture form on any webpage by including:
 *   <div id="auraboot-form"></div>
 *   <script src="https://your-host/api/crm/forms/__FORM_PID__/sdk.js"></script>
 *
 * The SDK endpoint injects real values for all __PLACEHOLDER__ tokens before serving.
 */
(function () {
  'use strict';

  var FORM_PID = '__FORM_PID__';
  var CHANNEL_PID = '__CHANNEL_PID__';
  var API_KEY = '__API_KEY__';
  var BASE_URL = '__BASE_URL__';

  // Locate the container element
  var container = document.getElementById('auraboot-form') ||
    document.querySelector('[data-auraboot-form]');
  if (!container) {
    console.warn('[AuraBoot] No container element found. Add <div id="auraboot-form"></div> to your page.');
    return;
  }

  // Inject base styles
  injectStyles();

  // Show loading indicator
  container.innerHTML = '<div class="ab-form-loading">Loading form...</div>';

  // Fetch form schema from public endpoint
  fetch(BASE_URL + '/api/crm/forms/' + FORM_PID + '/schema')
    .then(function (res) {
      if (!res.ok) {
        throw new Error('Failed to load form schema (HTTP ' + res.status + ')');
      }
      return res.json();
    })
    .then(function (response) {
      var schema = response.data || response;
      renderForm(container, schema);
    })
    .catch(function (err) {
      container.innerHTML = '<div class="ab-form-error">Unable to load form. Please try again later.</div>';
      console.error('[AuraBoot]', err);
    });

  /**
   * Renders the form HTML inside the container from the schema definition.
   */
  function renderForm(el, schema) {
    var fields = schema.formSchema || [];
    var styleConfig = schema.styleConfig || {};

    // Apply custom style config
    applyStyles(el, styleConfig);

    var html = '<form class="ab-form" id="ab-form-' + FORM_PID + '" novalidate>';

    if (schema.name) {
      html += '<h2 class="ab-form-title">' + escapeHtml(schema.name) + '</h2>';
    }

    fields.forEach(function (field) {
      html += renderField(field);
    });

    html += '<div class="ab-form-error-msg" id="ab-error-' + FORM_PID + '" style="display:none;"></div>';
    html += '<button type="submit" class="ab-form-submit">Submit</button>';
    html += '</form>';

    el.innerHTML = html;

    // Attach submit handler
    var form = el.querySelector('#ab-form-' + FORM_PID);
    if (form) {
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        handleSubmit(form, schema);
      });
    }
  }

  /**
   * Renders a single form field based on its type.
   */
  function renderField(field) {
    var name = field.name || '';
    var label = field.label || name;
    var type = field.type || 'text';
    var required = !!field.required;
    var placeholder = field.placeholder || '';
    var options = field.options || [];
    var requiredAttr = required ? ' required' : '';
    var requiredMark = required ? ' <span class="ab-required">*</span>' : '';

    var html = '<div class="ab-form-field">';
    html += '<label class="ab-form-label" for="ab-' + escapeHtml(name) + '">' +
      escapeHtml(label) + requiredMark + '</label>';

    if (type === 'textarea') {
      html += '<textarea class="ab-form-input" id="ab-' + escapeHtml(name) + '"' +
        ' name="' + escapeHtml(name) + '"' +
        ' placeholder="' + escapeHtml(placeholder) + '"' +
        requiredAttr + ' rows="4"></textarea>';
    } else if (type === 'select') {
      html += '<select class="ab-form-input" id="ab-' + escapeHtml(name) + '"' +
        ' name="' + escapeHtml(name) + '"' + requiredAttr + '>';
      html += '<option value="">-- Select --</option>';
      options.forEach(function (opt) {
        var val = typeof opt === 'object' ? opt.value : opt;
        var lbl = typeof opt === 'object' ? opt.label : opt;
        html += '<option value="' + escapeHtml(val) + '">' + escapeHtml(lbl) + '</option>';
      });
      html += '</select>';
    } else {
      // text, email, phone, number, url, etc.
      var inputType = (type === 'phone') ? 'tel' : type;
      html += '<input class="ab-form-input" id="ab-' + escapeHtml(name) + '"' +
        ' type="' + escapeHtml(inputType) + '"' +
        ' name="' + escapeHtml(name) + '"' +
        ' placeholder="' + escapeHtml(placeholder) + '"' +
        requiredAttr + ' />';
    }

    html += '<div class="ab-field-error" id="ab-err-' + escapeHtml(name) + '" style="display:none;"></div>';
    html += '</div>';
    return html;
  }

  /**
   * Validates and submits the form data to the inbound endpoint.
   */
  function handleSubmit(form, schema) {
    var fields = schema.formSchema || [];

    // Clear previous errors
    form.querySelectorAll('.ab-field-error').forEach(function (el) {
      el.style.display = 'none';
      el.textContent = '';
    });
    var errorMsg = document.getElementById('ab-error-' + FORM_PID);
    if (errorMsg) { errorMsg.style.display = 'none'; }

    // Client-side validation
    var firstError = null;
    var data = {};
    var valid = true;

    fields.forEach(function (field) {
      var name = field.name || '';
      var input = form.querySelector('[name="' + name + '"]');
      if (!input) return;

      var value = input.value.trim();
      data[name] = value;

      if (field.required && !value) {
        var errEl = document.getElementById('ab-err-' + name);
        if (errEl) {
          errEl.textContent = (field.label || name) + ' is required.';
          errEl.style.display = 'block';
        }
        if (!firstError) { firstError = input; }
        valid = false;
      } else if (field.type === 'email' && value && !isValidEmail(value)) {
        var errEl2 = document.getElementById('ab-err-' + name);
        if (errEl2) {
          errEl2.textContent = 'Please enter a valid email address.';
          errEl2.style.display = 'block';
        }
        if (!firstError) { firstError = input; }
        valid = false;
      }
    });

    if (!valid) {
      if (firstError) { firstError.focus(); }
      return;
    }

    // Disable submit button during submission
    var submitBtn = form.querySelector('.ab-form-submit');
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = 'Submitting...';
    }

    // Submit to the inbound endpoint
    var headers = {
      'Content-Type': 'application/json'
    };
    if (API_KEY) {
      headers['X-API-Key'] = API_KEY;
    }

    fetch(BASE_URL + '/api/crm/inbound/' + CHANNEL_PID + '/web-form', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify(data)
    })
      .then(function (res) {
        if (!res.ok) {
          throw new Error('Submission failed (HTTP ' + res.status + ')');
        }
        return res.json();
      })
      .then(function () {
        // Show success state
        if (schema.redirectUrl) {
          window.location.href = schema.redirectUrl;
        } else {
          var msg = schema.successMessage || 'Thank you! Your information has been submitted.';
          form.innerHTML = '<div class="ab-form-success">' + escapeHtml(msg) + '</div>';
        }
      })
      .catch(function (err) {
        if (submitBtn) {
          submitBtn.disabled = false;
          submitBtn.textContent = 'Submit';
        }
        if (errorMsg) {
          errorMsg.textContent = 'Submission failed. Please try again.';
          errorMsg.style.display = 'block';
        }
        console.error('[AuraBoot] Submission error:', err);
      });
  }

  // ==================== Utilities ====================

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function isValidEmail(email) {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
  }

  function applyStyles(el, styleConfig) {
    if (styleConfig.primaryColor) {
      el.style.setProperty('--ab-primary', styleConfig.primaryColor);
    }
    if (styleConfig.fontFamily) {
      el.style.fontFamily = styleConfig.fontFamily;
    }
    if (styleConfig.borderRadius) {
      el.style.setProperty('--ab-radius', styleConfig.borderRadius);
    }
  }

  function injectStyles() {
    if (document.getElementById('ab-form-styles')) return;
    var style = document.createElement('style');
    style.id = 'ab-form-styles';
    style.textContent = [
      '.ab-form { max-width: 480px; font-family: sans-serif; }',
      '.ab-form-title { margin-bottom: 16px; font-size: 20px; }',
      '.ab-form-field { margin-bottom: 16px; }',
      '.ab-form-label { display: block; margin-bottom: 4px; font-weight: 500; font-size: 14px; }',
      '.ab-required { color: #e53e3e; }',
      '.ab-form-input { display: block; width: 100%; padding: 8px 12px; border: 1px solid #d1d5db; border-radius: var(--ab-radius, 6px); font-size: 14px; box-sizing: border-box; }',
      '.ab-form-input:focus { outline: none; border-color: var(--ab-primary, #3b82f6); box-shadow: 0 0 0 2px rgba(59,130,246,0.2); }',
      '.ab-form-submit { display: block; width: 100%; padding: 10px 16px; background: var(--ab-primary, #3b82f6); color: #fff; border: none; border-radius: var(--ab-radius, 6px); font-size: 15px; cursor: pointer; }',
      '.ab-form-submit:disabled { opacity: 0.6; cursor: not-allowed; }',
      '.ab-field-error { color: #e53e3e; font-size: 12px; margin-top: 4px; }',
      '.ab-form-error-msg { color: #e53e3e; font-size: 13px; margin-bottom: 12px; padding: 8px 12px; background: #fff5f5; border-radius: 4px; }',
      '.ab-form-success { padding: 16px; background: #f0fff4; border: 1px solid #9ae6b4; border-radius: 6px; color: #276749; font-size: 15px; }',
      '.ab-form-loading { color: #6b7280; font-size: 14px; }',
      '.ab-form-error { color: #e53e3e; font-size: 14px; }'
    ].join('\n');
    document.head.appendChild(style);
  }

})();
