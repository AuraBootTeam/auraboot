import { sleep } from 'k6';
import { login } from './lib/http.js';

export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
  },
};

export default function () {
  login();
  sleep(1);
}
