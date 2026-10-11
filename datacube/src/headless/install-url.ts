// A headless bundle's second import (install-web.ts says why the order matters): URL, now that TextEncoder is there.

import { installUrl } from './url.ts';

installUrl();
