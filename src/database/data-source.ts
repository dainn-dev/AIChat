import 'reflect-metadata';
import { config as loadEnv } from 'dotenv';
import { DataSource } from 'typeorm';
import configuration from '../config/configuration';
import { buildDataSourceOptions } from './data-source-options';

// Standalone DataSource used by the TypeORM CLI (migration:run / generate /
// revert). Loads .env directly since the Nest DI container is not running here.
loadEnv();

const { database } = configuration();

export default new DataSource(buildDataSourceOptions(database));
